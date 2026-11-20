package com.streamly.metadata

import com.streamly.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal TMDB v3 client. Needs YOUR free key: put TMDB_KEY=... in local.properties. */
object Tmdb {
    // Some ISPs (notably in India) block the first host; the second serves the same API.
    private val hosts = listOf("api.themoviedb.org", "api.tmdb.org")
    private const val IMG = "https://image.tmdb.org/t/p"
    val enabled get() = BuildConfig.TMDB_KEY.isNotBlank()

    suspend fun get(path: String, vararg params: Pair<String, String>): JSONObject? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        val q = params.joinToString("") { "&${it.first}=${URLEncoder.encode(it.second, "UTF-8")}" }
        for (h in hosts) {
            val r = runCatching {
                val c = URL("https://$h/3$path?api_key=${BuildConfig.TMDB_KEY}$q").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null }
                finally { c.disconnect() }
            }.getOrNull()
            if (r != null) return@withContext r
        }
        null
    }

    fun poster(p: String?) = p?.let { "$IMG/w500$it" }
    fun backdrop(p: String?) = p?.let { "$IMG/w780$it" }
    fun still(p: String?) = p?.let { "$IMG/w300$it" }
}
