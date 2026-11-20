package com.streamly.plugins

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.webkit.CookieManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

sealed interface LinkKind {
    data object PluginFile : LinkKind
    data class PluginList(val items: List<RemotePlugin>) : LinkKind
    data class Repository(val listUrls: List<String>) : LinkKind
    data object Unknown : LinkKind
}

data class RemotePlugin(val name: String, val internalName: String, val url: String, val version: Int, val description: String?, val fileHash: String? = null)
/** What we recorded when a plugin was installed, so later versions can be recognised as updates. */
data class InstalledInfo(val version: Int, val url: String, val internalName: String)
data class PluginManifest(val name: String, val pluginClassName: String, val version: Int, val requiresResources: Boolean = false)

/**
 * Handles the CloudStream plugin contract:
 *  - repo.json      { name, manifestVersion, pluginLists: [url, ...] }
 *  - plugins.json   [ { name, internalName, url, version, status, ... } ]
 *  - <name>.cs3     zip with classes.dex + manifest.json { name, pluginClassName, version }
 *  - entry point    class annotated @CloudstreamPlugin extending BasePlugin (load()) or Plugin (load(Context));
 *                   load() calls registerMainAPI(...), which adds the provider to APIHolder.allProviders.
 */
class PluginManager(private val context: Context) {
    private val dir = File(context.filesDir, "plugins").apply { mkdirs() }
    private val info = context.getSharedPreferences("installed", Context.MODE_PRIVATE)
    private val registered = mutableMapOf<String, List<MainAPI>>()   // plugin -> providers it registered
    private val plugins = mutableMapOf<String, BasePlugin>()          // plugin file id -> live instance
    private val deferred = mutableSetOf<String>()                     // plugins whose settings bind to an Activity later

    // ---- recognize ----
    suspend fun classify(link: String): LinkKind = withContext(Dispatchers.IO) {
        val url = link.trim()
        require(url.startsWith("https://")) { "Only https links are accepted" }
        if (url.substringBefore('?').endsWith(".cs3", true)) return@withContext LinkKind.PluginFile
        val text = String(download(url, 2_000_000)).trimStart()
        when {
            text.startsWith("[") -> LinkKind.PluginList(parseList(JSONArray(text)))
            text.startsWith("{") -> JSONObject(text).optJSONArray("pluginLists")
                ?.let { a -> LinkKind.Repository((0 until a.length()).map(a::getString)) } ?: LinkKind.Unknown
            else -> LinkKind.Unknown
        }
    }

    suspend fun expand(repo: LinkKind.Repository): List<RemotePlugin> = withContext(Dispatchers.IO) {
        repo.listUrls.flatMap { u ->
            runCatching { parseList(JSONArray(String(download(u, 2_000_000)))) }.getOrDefault(emptyList())
        }
    }

    private fun parseList(a: JSONArray) = (0 until a.length()).mapNotNull { i ->
        val o = a.getJSONObject(i)
        if (o.optInt("status", 1) == 0) null            // status 0 = marked broken by its author
        else RemotePlugin(o.optString("name"), o.optString("internalName", o.optString("name")),
            o.getString("url"), o.optInt("version", 0), o.optString("description").ifBlank { null }, o.optString("fileHash").ifBlank { null })
    }

    // ---- install (call only after the user confirmed they trust the source) ----
    /**
     * [expectedHash] is the "sha256-<hex>" a repository's plugins.json lists for the file. When present, the download must match it,
     * which catches corrupted or swapped files. It does NOT prove the plugin is safe: whoever runs the repo controls both.
     */
    suspend fun install(url: String, expectedHash: String? = null, version: Int = 0, internalName: String? = null): PluginManifest = withContext(Dispatchers.IO) {
        require(url.startsWith("https://")) { "Only https links are accepted" }
        val tmp = File.createTempFile("dl", ".cs3", context.cacheDir)
        try {
            val bytes = download(url, 25_000_000)
            val want = expectedHash?.substringAfter("sha256-")?.lowercase()
            if (want != null && want.length == 64 && want.all { it in "0123456789abcdef" }) {
                val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(got == want) { "Checksum mismatch: the file doesn't match what the repository lists" }
            }
            tmp.writeBytes(bytes)
            val m = readManifest(tmp)
            val target = File(dir, m.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".cs3")
            unload(target.nameWithoutExtension)
            if (target.exists()) { target.setWritable(true); target.delete() }
            tmp.copyTo(target); target.setReadOnly()   // Android 14+: dex files must be read-only
            info.edit().putString(target.nameWithoutExtension, JSONObject().put("v", version).put("u", url)
                .put("n", internalName?.takeIf { it.isNotBlank() } ?: m.name).toString()).apply()
            load(target)
            m
        } finally { tmp.delete() }
    }

    fun installed(): List<File> = dir.listFiles { f -> f.extension == "cs3" }?.sortedBy { it.name }.orEmpty()
    fun providersOf(file: File) = registered[file.nameWithoutExtension].orEmpty()
    fun uninstall(file: File) {
        unload(file.nameWithoutExtension); file.setWritable(true); file.delete()
        info.edit().remove(file.nameWithoutExtension).apply()
    }

    fun installedInfo(): Map<String, InstalledInfo> = info.all.mapNotNull { (k, v) ->
        runCatching { JSONObject(v as String).let { k to InstalledInfo(it.getInt("v"), it.getString("u"), it.getString("n")) } }.getOrNull()
    }.toMap()

    // ---- load: runs the plugin entry point, then diffs APIHolder to learn what it registered ----
    @Synchronized
    fun load(file: File) {
        val key = file.nameWithoutExtension
        if (registered.containsKey(key)) return
        val manifest = readManifest(file)
        if (file.canWrite()) file.setReadOnly()                       // Android refuses writable dex
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val plugin = loader.loadClass(manifest.pluginClassName)
            .getDeclaredConstructor().newInstance() as BasePlugin
        plugin.filename = key        // registerMainAPI copies this into MainAPI.sourcePlugin
        if (manifest.requiresResources && plugin is Plugin) plugin.resources = buildResources(file)
        // Plugin (needs Context) vs BasePlugin (no args) - same dispatch Zangetsu uses.
        val err = runCatching { if (plugin is Plugin) plugin.load(context) else plugin.load() }.exceptionOrNull()
        val mine = APIHolder.allProviders.filter { it.sourcePlugin == key }
        // Some plugins throw after registering (e.g. casting context to an Activity): treat as success.
        if (err != null && mine.isEmpty()) throw err
        if (err?.message?.contains("Activity") == true) deferred.add(key)   // settings need a real Activity; bound on demand
        plugins[key] = plugin
        registered[key] = mine
    }

    fun loadAll() = installed().forEach { runCatching { load(it) } }

    @Synchronized
    fun unload(key: String) {
        plugins.remove(key); deferred.remove(key)
        registered.remove(key)?.let { APIHolder.allProviders.removeAll(it.toSet()) }
    }

    // ---- plugin settings ----

    /** True when the plugin exposes its own settings UI (bound now, or deferred until it gets an Activity). */
    fun hasSettings(file: File): Boolean {
        val key = file.nameWithoutExtension
        return (plugins[key] as? Plugin)?.openSettings != null || key in deferred
    }

    /**
     * Opens the plugin's settings against [activity] (must be an AppCompatActivity: plugins cast to it).
     * Some plugins capture `context as? AppCompatActivity` at load time, but we load with the application
     * context, so that capture is null. To fix it we create a second instance loaded WITH the activity, take its
     * freshly bound openSettings, and drop the duplicate providers that second load registered.
     */
    fun openSettings(key: String, activity: Context): Boolean {
        val file = installed().firstOrNull { it.nameWithoutExtension == key } ?: return false
        val opener = runCatching { freshOpener(file, key, activity) }.getOrNull()
            ?: (plugins[key] as? Plugin)?.openSettings
            ?: return false
        return runCatching { opener(activity); true }.getOrDefault(false)
    }

    private fun freshOpener(file: File, key: String, activity: Context): ((Context) -> Unit)? {
        val m = readManifest(file)
        val loader = PathClassLoader(file.absolutePath, context.classLoader)
        val p = loader.loadClass(m.pluginClassName).getDeclaredConstructor().newInstance() as? Plugin ?: return null
        p.filename = key
        if (m.requiresResources) p.resources = buildResources(file)
        val all = APIHolder.allProviders
        val before = synchronized(all) { all.toList() }
        runCatching { p.load(activity) }                       // binds openSettings; may register duplicates
        synchronized(all) {
            all.filter { it !in before }.forEach { dup -> all.remove(dup); runCatching { APIHolder.removePluginMapping(dup) } }
        }
        return p.openSettings
    }

    /** Plugins that declare requiresResources inflate layouts from their own .cs3, so give them a Resources for it. */
    @Suppress("DiscouragedPrivateApi")
    private fun buildResources(file: File): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, file.absolutePath)
        return Resources(assets, context.resources.displayMetrics, context.resources.configuration)
    }

    /** Clears one plugin's saved settings and its sites' cookies, leaving every other plugin alone. */
    fun resetPluginData(key: String): Boolean {
        var cleared = false
        val prefs = DataStore.getSharedPrefs(context)
        val file = installed().firstOrNull { it.nameWithoutExtension == key } ?: return false
        for (api in providersOf(file)) {
            val stale = prefs.all.keys.filter { it.startsWith("${api.name}/") }   // plugins store under "<provider name>/<key>"
            if (stale.isNotEmpty()) { prefs.edit().apply { stale.forEach { remove(it) } }.apply(); cleared = true }
            runCatching {
                val url = api.mainUrl.ifBlank { return@runCatching }
                val cm = CookieManager.getInstance()
                cm.getCookie(url)?.split(";")?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach {
                    cm.setCookie(url, "$it=; Max-Age=0; Path=/"); cleared = true
                }
                cm.flush()
            }
        }
        return cleared
    }

    private fun readManifest(f: File): PluginManifest = ZipFile(f).use { z ->
        requireNotNull(z.getEntry("classes.dex")) { "Not a plugin: classes.dex missing" }
        val e = requireNotNull(z.getEntry("manifest.json")) { "Not a plugin: manifest.json missing" }
        val j = JSONObject(z.getInputStream(e).readBytes().decodeToString())
        PluginManifest(j.getString("name"), j.getString("pluginClassName"), j.optInt("version", 0), j.optBoolean("requiresResources"))
    }

    private fun download(url: String, max: Int): ByteArray {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000 }
        try {
            require(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0
            c.inputStream.use { while (true) { val n = it.read(buf); if (n < 0) break; total += n; require(total <= max) { "File too large" }; out.write(buf, 0, n) } }
            return out.toByteArray()
        } finally { c.disconnect() }
    }
}
