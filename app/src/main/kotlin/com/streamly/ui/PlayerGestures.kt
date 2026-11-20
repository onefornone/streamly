package com.streamly.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private data class Hud(val label: String, val fraction: Float? = null)

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * Transparent gesture layer drawn over the video:
 *  - drag left/right            seek (full screen width = 90 s), committed when you lift your finger
 *  - drag up/down, LEFT half    brightness (this window only; restored when you leave the player)
 *  - drag up/down, RIGHT half   media volume
 *  - double-tap left/right      back / forward 10 s
 *  - single tap                 show/hide the controls ([onTap])
 * Leave the controller's own area (bottom of the video) uncovered so its seek bar and buttons keep working.
 */
@Composable
fun PlayerGestures(player: Player, activity: Activity?, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    val tap by rememberUpdatedState(onTap)

    var hud by remember { mutableStateOf<Hud?>(null) }
    var stamp by remember { mutableLongStateOf(0L) }
    fun show(label: String, fraction: Float? = null) { hud = Hud(label, fraction); stamp = System.nanoTime() }
    LaunchedEffect(stamp) { if (hud != null) { delay(700); hud = null } }   // each update restarts the timer

    fun brightness(): Float {
        val w = activity?.window ?: return 0.5f
        val b = w.attributes.screenBrightness
        if (b >= 0f) return b                                                // already overridden for this window
        return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }.getOrDefault(0.5f)
    }
    fun setBrightness(v: Float) {
        val w = activity?.window ?: return
        w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }   // 0 would switch the screen off
    }
    fun volume() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
    fun setVolume(v: Float) = audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * maxVol).roundToInt().coerceIn(0, maxVol), 0)
    fun seekBy(deltaMs: Long) {
        val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, dur))
    }

    Box(
        modifier
            // keyed on the player so a source failover (new player) never leaves these pointing at a released one
            .pointerInput(player) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { o ->
                        val back = o.x < size.width / 2
                        seekBy(if (back) -10_000L else 10_000L)
                        show(if (back) "−10 s" else "+10 s")
                    },
                )
            }
            .pointerInput(player) {
                var horizontal: Boolean? = null        // axis locks after the first ~24 px so a diagonal swipe can't do both
                var onLeft = true
                var dx = 0f; var dy = 0f
                var seekBase = 0L; var seekTarget = 0L
                var base = 0f
                detectDragGestures(
                    onDragStart = { o -> onLeft = o.x < size.width / 2; horizontal = null; dx = 0f; dy = 0f; seekBase = player.currentPosition; seekTarget = seekBase },
                    onDrag = { change, amount ->
                        change.consume()
                        dx += amount.x; dy += amount.y
                        if (horizontal == null) {
                            if (abs(dx) < 24f && abs(dy) < 24f) return@detectDragGestures
                            horizontal = abs(dx) > abs(dy)
                            if (horizontal == false) base = if (onLeft) brightness() else volume()
                        }
                        if (horizontal == true) {
                            if (!player.isCurrentMediaItemSeekable) return@detectDragGestures       // live streams can't seek
                            val dur = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                            seekTarget = (seekBase + (dx / size.width * 90_000f).toLong()).coerceIn(0L, dur)
                            val diff = (seekTarget - seekBase) / 1000
                            show("${fmt(seekTarget)}  (${if (diff >= 0) "+" else "−"}${abs(diff)} s)")
                        } else {
                            val v = (base - dy / size.height * 1.2f).coerceIn(0f, 1f)           // up = more; 1.2 so a full-height swipe covers the range
                            if (onLeft) { setBrightness(v); show("Brightness ${(v * 100).roundToInt()}%", v) }
                            else { setVolume(v); show("Volume ${(v * 100).roundToInt()}%", v) }
                        }
                    },
                    onDragEnd = { if (horizontal == true && player.isCurrentMediaItemSeekable) player.seekTo(seekTarget); horizontal = null },
                    onDragCancel = { horizontal = null },
                )
            },
    ) {
        hud?.let { h ->
            Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(12.dp), color = Color.Black.copy(alpha = 0.7f)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(h.label, color = Color.White)
                    h.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.width(140.dp).padding(top = 8.dp)) }
                }
            }
        }
    }
}
