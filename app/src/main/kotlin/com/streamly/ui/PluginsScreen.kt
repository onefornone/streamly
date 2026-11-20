package com.streamly.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamly.plugins.LinkKind
import com.streamly.plugins.PluginManager
import com.streamly.plugins.RemotePlugin
import com.streamly.plugins.RepoStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun PluginsScreen(pm: PluginManager) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repos = remember { RepoStore(context) }
    var saved by remember { mutableStateOf(repos.list()) }
    var input by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<RemotePlugin>>(emptyList()) }
    var filter by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(pm.installed()) }
    var resetKey by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var updatePending by remember { mutableStateOf<List<RemotePlugin>?>(null) }
    // Newer versions (from the repositories just loaded) of plugins that are already installed.
    val updates = remember(found, files) {
        val have = pm.installedInfo().values
        found.filter { f -> f.internalName.isNotBlank() && have.any { it.internalName == f.internalName && f.version > it.version } }
    }
    // Plugins load on a background thread at startup; re-read their state for a few seconds.
    LaunchedEffect(Unit) { repeat(6) { tick++; delay(1000) } }

    /** Fetches each link (repo, plugin list or single .cs3) and merges what they offer. Nothing is installed here. */
    suspend fun resolve(urls: List<String>, remember: Boolean) {
        busy = true; msg = ""
        val acc = LinkedHashMap<String, RemotePlugin>()
        var failed = 0
        for (u in urls) {
            runCatching { pm.classify(u) }.onSuccess { k ->
                when (k) {
                    is LinkKind.PluginFile -> acc[u] = RemotePlugin(u.substringBefore('?').substringAfterLast('/').removeSuffix(".cs3"), "", u, 0, null)
                    is LinkKind.PluginList -> { k.items.forEach { acc[it.url] = it }; if (remember) repos.add(u) }
                    is LinkKind.Repository -> { pm.expand(k).forEach { acc[it.url] = it }; if (remember) repos.add(u) }
                    LinkKind.Unknown -> failed++
                }
            }.onFailure { failed++ }
        }
        found = acc.values.toList(); saved = repos.list()
        msg = "${found.size} plugin(s) available" + if (failed > 0) " · $failed link(s) couldn't be read" else ""
        busy = false
    }

    LazyColumn(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
        item { Text("Plugins", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Paste repository links, one per line, or a link to a single .cs3 file.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("Repository or .cs3 URLs") }) }
        item {
            Button(enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), onClick = {
                val urls = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (urls.isNotEmpty()) scope.launch { resolve(urls, true) }
            }) { Text(if (busy) "Loading…" else "Add") }
        }
        if (msg.isNotEmpty()) item { Text(msg) }
        if (updates.isNotEmpty()) item {
            Button(modifier = Modifier.fillMaxWidth().height(48.dp), onClick = { updatePending = updates }) { Text("Update ${updates.size} installed plugin(s)") }
        }

        if (saved.isNotEmpty()) {
            item { Text("Saved repositories", style = MaterialTheme.typography.titleMedium) }
            items(saved) { u ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(u, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { scope.launch { resolve(listOf(u), false) } }) { Text("Load") }
                    TextButton(onClick = { repos.remove(u); saved = repos.list() }) { Text("Remove") }
                }
            }
        }

        if (found.isNotEmpty()) {
            item { Text("Available - tap to install", style = MaterialTheme.typography.titleMedium) }
            item { OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Filter by name") }) }
            items(found.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }) { p ->
                Text(if (p.version > 0) "${p.name}  v${p.version}" else p.name, Modifier.fillMaxWidth().clickable { pending = p.url }.padding(vertical = 10.dp))
            }
        }

        item { Text("Installed", style = MaterialTheme.typography.titleMedium) }
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        items(files) { f ->
            val key = f.nameWithoutExtension
            Column(Modifier.fillMaxWidth()) {
                Text("$key · ${pm.providersOf(f).size} source(s)", fontWeight = FontWeight.SemiBold)
                Row {
                    if (pm.hasSettings(f)) TextButton(onClick = {
                        context.startActivity(Intent(context, PluginSettingsActivity::class.java).putExtra(PluginSettingsActivity.EXTRA_PLUGIN, key))
                    }) { Text("Settings") }
                    TextButton(onClick = { resetKey = key }) { Text("Reset data") }
                    TextButton(onClick = { pm.uninstall(f); files = pm.installed() }) { Text("Remove") }
                }
            }
        }
    }

    updatePending?.let { list ->
        AlertDialog(onDismissRequest = { updatePending = null }, title = { Text("Update ${list.size} plugin(s)?") },
            text = { Text("Newer versions of plugins you already installed. Plugins are code that runs inside this app.\n\nFrom: ${list.mapNotNull { Uri.parse(it.url).host }.distinct().joinToString()}") },
            confirmButton = { TextButton(onClick = {
                updatePending = null
                scope.launch {
                    var ok = 0
                    for (p in list) if (runCatching { pm.install(p.url, p.fileHash, p.version, p.internalName) }.isSuccess) ok++
                    files = pm.installed(); msg = "Updated $ok of ${list.size}"
                }
            }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { updatePending = null }) { Text("Cancel") } })
    }
    pending?.let { u ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Install plugin?") },
            text = { Text("Plugins are code that runs inside this app with its permissions. Only install from sources you trust.\n\nFrom: ${Uri.parse(u).host ?: u}\n$u") },
            confirmButton = { TextButton(onClick = {
                pending = null
                scope.launch {
                    runCatching { found.firstOrNull { it.url == u }.let { meta -> pm.install(u, meta?.fileHash, meta?.version ?: 0, meta?.internalName) } }.onSuccess { msg = "Installed ${it.name}"; files = pm.installed() }
                        .onFailure { msg = it.message ?: "Error" }
                }
            }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
    resetKey?.let { k ->
        AlertDialog(onDismissRequest = { resetKey = null }, title = { Text("Reset plugin data?") },
            text = { Text("Clears this plugin's saved settings and its sites' cookies. Other plugins are not touched.") },
            confirmButton = { TextButton(onClick = {
                msg = if (pm.resetPluginData(k)) "Cleared data for $k" else "Nothing to clear for $k"; resetKey = null
            }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetKey = null }) { Text("Cancel") } })
    }
}
