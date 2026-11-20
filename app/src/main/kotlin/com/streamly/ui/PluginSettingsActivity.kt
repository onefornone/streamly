package com.streamly.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.lagradost.cloudstream3.CommonActivity
import com.streamly.App

/**
 * Transparent host for a plugin's OWN settings UI. Plugins expose `openSettings(Context)` and expect an
 * AppCompatActivity: some show a BottomSheetDialogFragment on supportFragmentManager, others a plain
 * AlertDialog on the window. Our main screen is a ComponentActivity, so we launch this one, hand it to the
 * plugin, and finish as soon as the sheet/dialog goes away.
 */
class PluginSettingsActivity : AppCompatActivity() {
    private var dialogHadFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_PLUGIN)
        if (key == null) { finish(); return }
        CommonActivity.setActivityInstance(this)
        if (savedInstanceState == null) {
            supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) { if (fm.fragments.isEmpty()) finish() }
            }, false)
            val shown = (application as App).plugins.openSettings(key, this)
            if (!shown) { Toast.makeText(this, "This plugin has no settings", Toast.LENGTH_SHORT).show(); finish() }
            // No "finish if no fragment" timer on purpose: plain-dialog plugins add no fragment (see below).
        }
    }

    // A plain AlertDialog takes window focus from this activity; when it is dismissed focus returns - our cue to leave.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) { dialogHadFocus = true; return }
        if (dialogHadFocus && supportFragmentManager.fragments.isEmpty()) finish()
    }

    override fun onDestroy() {
        if (CommonActivity.activity === this) CommonActivity.setActivityInstance(null)
        super.onDestroy()
    }

    companion object { const val EXTRA_PLUGIN = "plugin" }
}
