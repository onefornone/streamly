package com.streamly.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class WatchEntry(
    val apiName: String, val url: String, val title: String, val poster: String?,
    val episodeData: String, val episodeName: String,
    val positionMs: Long, val durationMs: Long, val updatedAt: Long,
)
data class Bookmark(val apiName: String, val url: String, val title: String, val poster: String?)

/** Watch history (resume + Continue Watching) and My List, in SharedPreferences. Swap for Room when it grows. */
class WatchStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("watch", Context.MODE_PRIVATE)
    private fun arr(k: String) = JSONArray(prefs.getString(k, "[]"))
    private fun JSONObject.s(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }

    fun all(): List<WatchEntry> = arr("history").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
            WatchEntry(o.getString("api"), o.getString("url"), o.getString("title"), o.s("poster"),
                o.getString("data"), o.optString("ep"), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        } }
    }

    fun continueWatching() = all()
        .filter { it.durationMs > 0 && it.positionMs > 10_000 && it.positionMs < it.durationMs * 0.95 }
        .sortedByDescending { it.updatedAt }.distinctBy { it.apiName + it.url }.take(20)

    fun position(episodeData: String): Long = all().firstOrNull { it.episodeData == episodeData }
        ?.takeIf { it.durationMs == 0L || it.positionMs < it.durationMs * 0.95 }?.positionMs ?: 0L

    fun lastFor(api: String, url: String) = all().filter { it.apiName == api && it.url == url }.maxByOrNull { it.updatedAt }

    @Synchronized fun save(e: WatchEntry) {
        val list = (all().filter { it.episodeData != e.episodeData } + e).sortedByDescending { it.updatedAt }.take(300)
        prefs.edit().putString("history", JSONArray(list.map {
            JSONObject().put("api", it.apiName).put("url", it.url).put("title", it.title).put("poster", it.poster)
                .put("data", it.episodeData).put("ep", it.episodeName).put("pos", it.positionMs)
                .put("dur", it.durationMs).put("at", it.updatedAt)
        }).toString()).apply()
    }

    fun bookmarks(): List<Bookmark> = arr("list").let { a ->
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Bookmark(it.getString("api"), it.getString("url"), it.getString("title"), it.s("poster")) } }
    }
    fun isBookmarked(api: String, url: String) = bookmarks().any { it.apiName == api && it.url == url }

    @Synchronized fun toggleBookmark(b: Bookmark): Boolean {
        val had = isBookmarked(b.apiName, b.url)
        val list = if (had) bookmarks().filterNot { it.apiName == b.apiName && it.url == b.url } else bookmarks() + b
        prefs.edit().putString("list", JSONArray(list.map {
            JSONObject().put("api", it.apiName).put("url", it.url).put("title", it.title).put("poster", it.poster)
        }).toString()).apply()
        return !had
    }
}
