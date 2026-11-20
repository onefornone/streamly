package com.streamly.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.streamly.core.Ep
import com.streamly.core.ProviderHost
import com.streamly.data.Bookmark
import com.streamly.metadata.EpMeta
import com.streamly.metadata.Meta
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class PlayCtx(
    val apiName: String, val url: String, val title: String, val poster: String?,
    val episodeData: String, val episodeName: String,
)

@Composable
fun DetailScreen(d: Route.Detail, onBack: () -> Unit, onPlay: (List<ExtractorLink>, List<SubtitleFile>, PlayCtx) -> Unit) {
    val app = LocalContext.current.streamly
    val scope = rememberCoroutineScope()
    val api = remember(d) { ProviderHost.providers().firstOrNull { it.name == d.apiName } }
    var load by remember { mutableStateOf<LoadResponse?>(null) }
    var meta by remember { mutableStateOf<Meta?>(null) }
    var eps by remember { mutableStateOf<List<Ep>>(emptyList()) }
    var epMeta by remember { mutableStateOf<Map<Int, Map<Int, EpMeta>>>(emptyMap()) }
    var status by remember { mutableStateOf("Loading…") }
    var picker by remember { mutableStateOf<Triple<List<ExtractorLink>, List<SubtitleFile>, Ep>?>(null) }
    var saved by remember { mutableStateOf(app.watch.isBookmarked(d.apiName, d.url)) }

    LaunchedEffect(d) {
        if (api == null) { status = "That source isn't installed any more"; return@LaunchedEffect }
        val r = runCatching { withTimeout(25_000) { ProviderHost.load(api, d.url) } }.getOrNull()
        if (r == null) { status = "Couldn't load this title"; return@LaunchedEffect }
        load = r; eps = ProviderHost.episodes(r); status = ""
        val tv = when (r) { is MovieLoadResponse -> false; is TvSeriesLoadResponse, is AnimeLoadResponse -> true; else -> null }
        val m = app.meta.find(r.name, tv); meta = m
        if (m != null && m.isTv) {                                   // episode titles + stills, season by season
            val acc = mutableMapOf<Int, Map<Int, EpMeta>>()
            for (s in eps.map { it.season }.distinct().sorted().take(5)) { acc[s] = app.meta.episodes(m.tmdbId, s); epMeta = acc.toMap() }
        }
    }

    fun ctx(ep: Ep) = PlayCtx(d.apiName, d.url, load?.name ?: d.title, d.poster, ep.data, ep.label)
    fun play(ep: Ep) {
        scope.launch {
            status = "Finding streams…"
            val res = runCatching { ProviderHost.links(api!!, ep.data) }.getOrNull()
            status = ""
            if (res == null) { status = "Couldn't get streams"; return@launch }
            val (links, subs) = res
            when {
                links.isEmpty() -> status = "No streams found"
                links.size == 1 -> onPlay(links, subs, ctx(ep))
                else -> picker = Triple(links.sortedByDescending { it.quality }, subs, ep)
            }
        }
    }

    val last = remember(d) { app.watch.lastFor(d.apiName, d.url) }
    val resumeEp = eps.firstOrNull { it.data == last?.episodeData } ?: eps.firstOrNull()

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Column {
            Box {
                Img(meta?.backdrop ?: d.poster, d.title, Modifier.fillMaxWidth().height(240.dp))
                TextButton(onClick = onBack, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) { Text("‹ Back") }
            }
            Column(Modifier.padding(20.dp, 16.dp, 20.dp, 0.dp)) {
                Text(load?.name ?: d.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val line = listOfNotNull(meta?.year, meta?.rating?.let { "★ %.1f".format(it) }, d.apiName).joinToString(" · ")
                Text(line, Modifier.padding(top = 4.dp, bottom = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { resumeEp?.let(::play) }, enabled = resumeEp != null, modifier = Modifier.weight(1f).height(48.dp)) {
                        Text(if (last != null) "Resume" else "Play")
                    }
                    OutlinedButton(onClick = { saved = app.watch.toggleBookmark(Bookmark(d.apiName, d.url, d.title, d.poster)) },
                        modifier = Modifier.weight(1f).height(48.dp)) { Text(if (saved) "✓ In list" else "+ My list") }
                }
                val plot = load?.plot ?: meta?.overview
                if (!plot.isNullOrBlank()) Text(plot, Modifier.padding(top = 12.dp), maxLines = 5, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (status.isNotEmpty()) Text(status, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.primary)
                if (eps.size > 1) Text("Episodes", Modifier.padding(top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        } }
        if (eps.size > 1) items(eps) { ep ->
            val m = epMeta[ep.season]?.get(ep.number ?: -1)
            Row(Modifier.fillMaxWidth().clickable { play(ep) }.padding(20.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Img(ep.still ?: m?.still, ep.label, Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(8.dp)))
                Column(Modifier.weight(1f)) {
                    Text("S${ep.season}·E${ep.number ?: "?"}  ${m?.name ?: ep.label}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    val desc = ep.desc ?: m?.overview
                    if (!desc.isNullOrBlank()) Text(desc, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    picker?.let { (links, subs, ep) ->
        AlertDialog(onDismissRequest = { picker = null }, title = { Text("Choose a stream") },
            text = { LazyColumn { items(links.size) { i ->
                val l = links[i]
                TextButton(onClick = { picker = null; onPlay(links.drop(i) + links.take(i), subs, ctx(ep)) }) {
                    Text("${l.source}${if (l.quality > 0) " · ${l.quality}p" else ""}")
                }
            } } },
            confirmButton = {}, dismissButton = { TextButton(onClick = { picker = null }) { Text("Cancel") } })
    }
}
