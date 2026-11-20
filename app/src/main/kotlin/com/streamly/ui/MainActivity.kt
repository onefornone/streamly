package com.streamly.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.streamly.App
import com.streamly.plugins.PluginManager

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object Plugins : Route
    data class Detail(val apiName: String, val url: String, val title: String, val poster: String?) : Route
    data class Play(val links: List<ExtractorLink>, val subs: List<SubtitleFile>, val ctx: PlayCtx) : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pm = (application as App).plugins
        setContent { MaterialTheme(colorScheme = StreamlyColors) { Surface(Modifier.fillMaxSize()) { Root(pm) } } }
    }

    // API 26-30: enter PiP when the user leaves while a video plays. API 31+ does it via setAutoEnterEnabled.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipController.armed && Build.VERSION.SDK_INT < 31) PipController.enter(this)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipController.inPip = isInPictureInPictureMode
    }
}

/** stack[0] is the selected tab; anything above it (Detail, Play) is drawn over the tab, so tab state survives. */
@Composable
fun Root(pm: PluginManager) {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val overlay = stack.drop(1).lastOrNull()
    BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
    val open = { d: Route.Detail -> stack.add(d); Unit }

    Box(Modifier.fillMaxSize()) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Home" to Route.Home, "Search" to Route.Search, "Library" to Route.Library, "Plugins" to Route.Plugins).forEach { (label, r) ->
                    NavigationBarItem(selected = stack[0] == r, onClick = { stack.clear(); stack.add(r) }, icon = {}, label = { Text(label) })
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                when (stack[0]) {
                    Route.Home -> HomeScreen(open)
                    Route.Search -> SearchScreen(open)
                    Route.Library -> LibraryScreen(open)
                    else -> PluginsScreen(pm)
                }
            }
        }
        when (overlay) {
            is Route.Detail -> Surface(Modifier.fillMaxSize()) {
                DetailScreen(overlay, onBack = { stack.removeAt(stack.lastIndex) }, onPlay = { l, s, c -> stack.add(Route.Play(l, s, c)) })
            }
            is Route.Play -> Surface(Modifier.fillMaxSize()) { PlayerScreen(overlay.links, overlay.subs, overlay.ctx) { stack.removeAt(stack.lastIndex) } }
            else -> {}
        }
    }
}
