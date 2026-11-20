package com.streamly.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.streamly.core.ProviderHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

@Composable
fun HomeScreen(onOpen: (Route.Detail) -> Unit) {
    val app = LocalContext.current.streamly
    var providers by remember { mutableStateOf(ProviderHost.providers()) }
    var selected by remember { mutableStateOf<MainAPI?>(null) }
    var rows by remember { mutableStateOf<List<HomePageList>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val resume = remember { app.watch.continueWatching() }

    // Plugins load on a background thread at startup, so poll briefly for them.
    LaunchedEffect(Unit) {
        repeat(8) { providers = ProviderHost.providers(); if (selected == null) selected = providers.firstOrNull(); delay(1000) }
    }
    LaunchedEffect(selected) {
        val api = selected ?: return@LaunchedEffect
        status = "Loading ${api.name}…"; rows = emptyList()
        runCatching { withTimeout(25_000) { ProviderHost.home(api) } }
            .onSuccess { rows = it; status = if (it.isEmpty()) "${api.name} has no home page - try Search" else "" }
            .onFailure { status = "Couldn't load ${api.name}" }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Streamly", Modifier.padding(20.dp, 24.dp, 20.dp, 8.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        if (providers.isEmpty()) {
            item { Text("No sources yet. Add a plugin repository on the Plugins tab.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(providers) { p -> FilterChip(selected = p == selected, onClick = { selected = p }, label = { Text(p.name) }) }
            }
        }
        if (status.isNotEmpty()) item { Text(status, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (resume.isNotEmpty()) {
            item { SectionTitle("Continue watching") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(resume) { e ->
                        PosterCard("${e.title} · ${e.episodeName}", e.poster, null, Modifier.width(112.dp),
                            progress = e.positionMs.toFloat() / e.durationMs) { onOpen(Route.Detail(e.apiName, e.url, e.title, e.poster)) }
                    }
                }
            }
        }
        items(rows) { row -> Column {
            SectionTitle(row.name)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(row.list) { r ->
                    PosterCard(r.name, r.posterUrl, r.posterHeaders, Modifier.width(112.dp)) { onOpen(Route.Detail(r.apiName, r.url, r.name, r.posterUrl)) }
                }
            }
        } }
    }
}

@Composable
fun SearchScreen(onOpen: (Route.Detail) -> Unit) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResponse>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    fun run() { if (q.isBlank()) return; scope.launch { busy = true; results = ProviderHost.searchAll(q.trim()); busy = false; searched = true } }

    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("Search", Modifier.padding(top = 24.dp, bottom = 12.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search all sources") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run() }))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        else if (searched && results.isEmpty()) Text("No results", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(results) { r -> PosterCard(r.name, r.posterUrl, r.posterHeaders) { onOpen(Route.Detail(r.apiName, r.url, r.name, r.posterUrl)) } }
        }
    }
}

@Composable
fun LibraryScreen(onOpen: (Route.Detail) -> Unit) {
    val app = LocalContext.current.streamly
    val saved = remember { app.watch.bookmarks() }
    val resume = remember { app.watch.continueWatching() }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Library", Modifier.padding(20.dp, 24.dp, 20.dp, 0.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { SectionTitle("My list") }
        if (saved.isEmpty()) item { Text("Nothing saved yet. Tap + My list on a title.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(saved) { b -> PosterCard(b.title, b.poster, null, Modifier.width(112.dp)) { onOpen(Route.Detail(b.apiName, b.url, b.title, b.poster)) } }
            }
        }
        item { SectionTitle("History") }
        if (resume.isEmpty()) item { Text("Nothing in progress.", Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(resume) { e -> PosterCard("${e.title} · ${e.episodeName}", e.poster, null, Modifier.width(112.dp),
                    progress = e.positionMs.toFloat() / e.durationMs) { onOpen(Route.Detail(e.apiName, e.url, e.title, e.poster)) } }
            }
        }
    }
}
