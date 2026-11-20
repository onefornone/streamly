package com.streamly.metadata

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class Meta(
    val tmdbId: Int, val isTv: Boolean, val title: String,
    val poster: String?, val backdrop: String?, val overview: String?, val rating: Double?, val year: String?,
)
data class EpMeta(val name: String?, val still: String?, val overview: String?)

/**
 * Title -> TMDB match -> poster / backdrop / synopsis / episode stills, cached on disk for a week.
 * Matching is strict (normalized exact title) so a wrong poster is rarer than a missing one.
 */
class MetadataRepository(context: Context) {
    private val dir = File(context.cacheDir, "meta").apply { mkdirs() }
    private val ttlMs = 7L * 24 * 3600 * 1000

    private fun file(key: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")

    private fun read(key: String): JSONObject? = file(key)
        .takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < ttlMs }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    private fun write(key: String, o: JSONObject) { runCatching { file(key).writeText(o.toString()) } }

    private fun clean(t: String) = t.replace(Regex("[\\(\\[].*?[\\)\\]]"), "").trim()
    private fun norm(t: String) = clean(t).lowercase().replace(Regex("[^a-z0-9]"), "")
    private fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }

    /** [tv]: true = series/anime, false = movie, null = unknown. */
    suspend fun find(title: String, tv: Boolean? = null): Meta? {
        val key = "find|${norm(title)}|$tv"
        read(key)?.let { return if (it.optBoolean("miss")) null else it.toMeta() }
        val results = Tmdb.get("/search/multi", "query" to clean(title))?.optJSONArray("results")
            ?: return null                                   // network/no key: don't cache a miss
        for (i in 0 until results.length()) {
            val o = results.getJSONObject(i)
            val type = o.optString("media_type")
            if (type != "movie" && type != "tv") continue
            if (tv != null && (type == "tv") != tv) continue
            val name = o.str(if (type == "tv") "name" else "title") ?: continue
            if (norm(name) != norm(title)) continue
            val date = o.str(if (type == "tv") "first_air_date" else "release_date")
            val m = Meta(o.getInt("id"), type == "tv", name, Tmdb.poster(o.str("poster_path")),
                Tmdb.backdrop(o.str("backdrop_path")), o.str("overview"),
                o.optDouble("vote_average").takeIf { !it.isNaN() && it > 0 }, date?.take(4))
            write(key, m.toJson()); return m
        }
        write(key, JSONObject().put("miss", true))
        return null
    }

    /** Episode titles/stills for one TMDB season, keyed by episode number. */
    suspend fun episodes(tmdbId: Int, season: Int): Map<Int, EpMeta> {
        val key = "ep|$tmdbId|$season"
        val j = read(key) ?: (Tmdb.get("/tv/$tmdbId/season/$season")?.also { write(key, it) } ?: return emptyMap())
        val arr = j.optJSONArray("episodes") ?: JSONArray()
        return (0 until arr.length()).associate {
            val e = arr.getJSONObject(it)
            e.optInt("episode_number") to EpMeta(e.str("name"), Tmdb.still(e.str("still_path")), e.str("overview"))
        }
    }

    private fun Meta.toJson() = JSONObject().put("id", tmdbId).put("tv", isTv).put("title", title)
        .put("poster", poster).put("backdrop", backdrop).put("overview", overview)
        .put("rating", rating ?: JSONObject.NULL).put("year", year)

    private fun JSONObject.toMeta() = Meta(getInt("id"), getBoolean("tv"), getString("title"), str("poster"),
        str("backdrop"), str("overview"), optDouble("rating").takeIf { !it.isNaN() }, str("year"))
}
