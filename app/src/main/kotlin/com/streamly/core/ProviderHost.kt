package com.streamly.core

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.Collections

/** One playable episode (a movie is a single episode). */
data class Ep(val label: String, val season: Int, val number: Int?, val data: String, val still: String?, val desc: String?)

/** Thin wrapper over what plugins register: providers -> home, search, load, links. */
object ProviderHost {
    fun providers(): List<MainAPI> = APIHolder.allProviders.toList()

    suspend fun home(api: MainAPI): List<HomePageList> {
        val req = api.mainPage.firstOrNull() ?: return emptyList()
        return api.getMainPage(1, req)?.items.orEmpty()
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> = api.search(query) ?: emptyList()

    /** Searches every installed source at once; a slow or broken source never blocks the rest. */
    suspend fun searchAll(query: String): List<SearchResponse> = coroutineScope {
        providers().map { api ->
            async { runCatching { withTimeout(15_000) { search(api, query) } }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun load(api: MainAPI, url: String): LoadResponse? = api.load(url)

    fun episodes(r: LoadResponse): List<Ep> = when (r) {
        is MovieLoadResponse -> listOf(Ep(r.name, 1, 1, r.dataUrl, null, null))
        is TvSeriesLoadResponse -> r.episodes.mapIndexed { i, e -> e.toEp(i) }
        is AnimeLoadResponse -> (r.episodes[DubStatus.Subbed] ?: r.episodes.values.firstOrNull() ?: emptyList())
            .mapIndexed { i, e -> e.toEp(i) }
        else -> emptyList()
    }

    private fun Episode.toEp(i: Int) =
        Ep(name ?: "Episode ${episode ?: i + 1}", season ?: 1, episode ?: (i + 1), data, posterUrl, description)

    /** Runs the provider's extractors and collects every link + subtitle they emit. */
    suspend fun links(api: MainAPI, data: String): Pair<List<ExtractorLink>, List<SubtitleFile>> {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        api.loadLinks(data, false, { subs.add(it) }, { links.add(it) })
        return links.toList() to subs.toList()
    }
}
