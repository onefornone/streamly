package com.streamly.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Built-in source so the app works on first launch with no plugins: the Internet Archive's free film and TV
 * collections (mostly public-domain). It is also a small, complete example of what a CloudStream provider is:
 * a MainAPI with getMainPage / search / load / loadLinks - the same class every .cs3 plugin registers.
 */
class ArchiveProvider : MainAPI() {
    override var name = "Internet Archive"
    override var mainUrl = "https://archive.org"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "en"
    override val hasMainPage = true
    override val mainPage = mainPageOf(               // data to display-name
        "collection:feature_films" to "Feature films",
        "collection:silent_films" to "Silent films",
        "collection:classic_tv" to "Classic TV",
        "collection:animationandcartoons" to "Animation & cartoons",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // Plain HTTP on purpose: this built-in source needs nothing from the plugin runtime.
    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000; c.setRequestProperty("User-Agent", "Streamly/1.0")
        try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    private suspend fun fetch(q: String, page: Int): List<SearchResponse> {
        val url = "$mainUrl/advancedsearch.php?q=${enc("($q) AND mediatype:movies")}" +
            "&fl[]=identifier&fl[]=title&rows=50&page=$page&sort[]=${enc("downloads desc")}&output=json"
        val docs = JSONObject(httpGet(url)).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { i ->
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier").ifBlank { return@mapNotNull null }
            newMovieSearchResponse(d.optString("title", id), "$mainUrl/details/$id", TvType.Movie) {
                posterUrl = "$mainUrl/services/img/$id"
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = fetch(request.data, page)
        return newHomePageResponse(request.name, items, hasNext = items.size >= 50)
    }

    override suspend fun search(query: String): List<SearchResponse> =
        fetch("title:(${query.replace("\"", " ")})", 1)

    override suspend fun load(url: String): LoadResponse {
        val id = url.trimEnd('/').substringAfterLast('/')
        val meta = JSONObject(httpGet("$mainUrl/metadata/$id")).optJSONObject("metadata")
        val title = meta?.optString("title")?.takeIf { it.isNotBlank() } ?: id
        return newMovieLoadResponse(title, url, TvType.Movie, id) {
            posterUrl = "$mainUrl/services/img/$id"
            plot = meta?.optString("description")?.replace(Regex("<[^>]*>"), "")?.takeIf { it.isNotBlank() }
            year = meta?.optString("year")?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val files = JSONObject(httpGet("$mainUrl/metadata/$data")).optJSONArray("files") ?: return false
        val mp4s = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp4", true) }
            // h.264 derivatives play everywhere; the original upload may be any codec, so it goes later.
            .sortedWith(compareByDescending<JSONObject> { it.optString("format").contains("264") }
                .thenByDescending { it.optString("size").toLongOrNull() ?: 0L })
            .take(3)
        mp4s.forEach { f ->
            val path = f.getString("name").split('/').joinToString("/") { enc(it).replace("+", "%20") }
            callback(newExtractorLink(name, f.getString("name").substringAfterLast('/'), "$mainUrl/download/$data/$path", ExtractorLinkType.VIDEO))
        }
        return mp4s.isNotEmpty()
    }
}
