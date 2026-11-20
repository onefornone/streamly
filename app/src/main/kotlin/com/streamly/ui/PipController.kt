package com.streamly.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Picture-in-picture state shared between MainActivity (system callbacks) and the player (Compose). */
object PipController {
    /** True while the activity is shown in the PiP window. Read from Compose, written by MainActivity. */
    var inPip by mutableStateOf(false)

    /** True while a video is playing: only then may the system auto-enter PiP when the user leaves the app. */
    @Volatile var armed = false
    @Volatile var aspect: Rational = Rational(16, 9)

    fun supported(a: Activity) = Build.VERSION.SDK_INT >= 26 &&
        a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun params(): PictureInPictureParams {
        val ratio = aspect.toFloat()
        val safe = if (ratio in 0.42f..2.39f) aspect else Rational(16, 9)   // outside this range the system throws
        return PictureInPictureParams.Builder().setAspectRatio(safe).apply {
            if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(armed)       // API 31+: Home gesture enters PiP by itself
        }.build()
    }

    fun update(a: Activity) { if (supported(a)) runCatching { a.setPictureInPictureParams(params()) } }
    fun enter(a: Activity) { if (supported(a)) runCatching { a.enterPictureInPictureMode(params()) } }
}
