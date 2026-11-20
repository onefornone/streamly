package com.streamly.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.streamly.data.WatchEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Plays links[0] (fails over to the next on error), resumes and saves progress.
 *  - Fullscreen: hides system bars, fills the screen. Entered by the player's fullscreen button or by turning the phone sideways.
 *  - Landscape: the activity handles rotation itself (manifest configChanges), so the player never restarts.
 *  - PiP: button, or automatically when you leave the app while playing.
 */
@Composable
fun PlayerScreen(links: List<ExtractorLink>, subs: List<SubtitleFile>, ctx: PlayCtx, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val app = context.streamly
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    val link = links[index]
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fullscreen by remember { mutableStateOf(landscape) }
    val inPip = PipController.inPip
    val originalBrightness = remember { activity?.window?.attributes?.screenBrightness ?: -1f }   // gestures change it; restored on exit
    val playerView = remember { arrayOfNulls<PlayerView>(1) }

    val player = remember(index) {
        val headers = buildMap { putAll(link.headers); if (link.referer.isNotBlank()) put("Referer", link.referer) }
        val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        val item = MediaItem.Builder().setUri(link.url)
            .apply { if (link.type == ExtractorLinkType.M3U8) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subs.map {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                    .setMimeType(if (it.url.substringBefore('?').endsWith(".srt")) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
                    .setLanguage(it.lang).build()
            }).build()
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
            setMediaItem(item)
            val resume = app.watch.position(ctx.episodeData)
            if (resume > 10_000) seekTo(resume - 2_000)
            prepare(); playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { if (index + 1 < links.size) index += 1 }
                // PiP is only auto-entered while something is actually playing.
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    PipController.armed = isPlaying
                    activity?.let { PipController.update(it) }
                }
                // The PiP window takes the video's real shape (16:9, 4:3, ...).
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        PipController.aspect = Rational((size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1), size.height)
                        activity?.let { PipController.update(it) }
                    }
                }
            })
        }
    }

    fun saveProgress() {
        val dur = player.duration
        if (dur <= 0) return
        app.watch.save(WatchEntry(ctx.apiName, ctx.url, ctx.title, ctx.poster, ctx.episodeData, ctx.episodeName,
            player.currentPosition, dur, System.currentTimeMillis()))
    }
    LaunchedEffect(player) { while (true) { delay(5_000); saveProgress() } }
    DisposableEffect(player) {
        // ON_STOP covers backgrounding AND closing the PiP window (which stops the activity): pause, don't keep playing unseen.
        val lifecycle = (activity as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); saveProgress(); player.release() }
    }

    // ---- fullscreen / landscape ----
    fun toggleFullscreen() {
        val act = activity ?: return
        val sideways = act.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!fullscreen) {
            fullscreen = true
            if (!sideways) act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            fullscreen = false
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // Release the lock shortly after so auto-rotate works again.
            scope.launch { delay(1_200); act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
    LaunchedEffect(landscape) { fullscreen = landscape }          // turning the phone sideways/upright follows suit
    BackHandler(enabled = fullscreen && !inPip) { toggleFullscreen() }

    DisposableEffect(fullscreen, inPip) {
        activity?.window?.let { w ->
            val bars = WindowCompat.getInsetsController(w, w.decorView)
            if (fullscreen && !inPip) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES   // use the notch area
                }
            } else bars.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
    DisposableEffect(Unit) {
        onDispose {                                                // leaving the player: restore everything
            activity?.let { a ->
                WindowCompat.getInsetsController(a.window, a.window.decorView).show(WindowInsetsCompat.Type.systemBars())
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                if (Build.VERSION.SDK_INT >= 28) a.window.attributes = a.window.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                a.window.attributes = a.window.attributes.apply { screenBrightness = originalBrightness }
                PipController.armed = false
                PipController.update(a)
            }
        }
    }

    val pipAvailable = activity?.let { PipController.supported(it) } == true
    val full = fullscreen || inPip
    Column(Modifier.fillMaxSize().then(if (full) Modifier.background(Color.Black) else Modifier)) {
        Box(if (full) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { keepScreenOn = true; setFullscreenButtonClickListener { toggleFullscreen() }; playerView[0] = this } },
                update = { it.player = player; it.useController = !inPip },
                modifier = Modifier.fillMaxSize(),
            )
            if (!inPip) {
                // Bottom 96 dp is left free for the controller's own seek bar and buttons.
                PlayerGestures(player, activity, modifier = Modifier.fillMaxSize().padding(bottom = 96.dp), onTap = {
                    playerView[0]?.let { if (it.isControllerFullyVisible) it.hideController() else it.showController() }
                })
            }
            if (pipAvailable && !inPip) {
                TextButton(onClick = { activity?.let { PipController.enter(it) } }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) { Text("PiP") }
            }
        }
        if (!full) {
            Text("${ctx.title} · ${ctx.episodeName}", Modifier.padding(16.dp))
            Text("Source ${index + 1} of ${links.size}: ${link.source}", Modifier.padding(horizontal = 16.dp))
            TextButton(onClick = onBack) { Text("‹ Back") }
        }
    }
}
