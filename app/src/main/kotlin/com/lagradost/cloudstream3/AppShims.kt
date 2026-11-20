package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.Event

/**
 * Stand-ins for CloudStream app-module classes that plugins reference but the `library` artifact lacks.
 * Without them those plugins crash with NoClassDefFoundError as soon as they touch settings or toasts.
 * Package + member names must stay identical to upstream so already-compiled plugins link.
 */
class CloudStreamApp {
    companion object {
        @Volatile private var appContext: Context? = null
        fun setContext(context: Context) { appContext = context.applicationContext }
        fun getContext(): Context? = appContext

        fun setKey(path: String, value: Any?) = DataStore.setKey(path, value)
        fun <T> getKey(path: String): T? = getKey(path, null)
        @Suppress("UNCHECKED_CAST")
        fun <T> getKey(path: String, defVal: T?): T? = (DataStore.getKey(path, Any::class.java) as? T) ?: defVal
        fun removeKey(path: String) {
            appContext?.let { DataStore.getSharedPrefs(it).edit().remove(path).apply() }
        }
    }
}

object CommonActivity {
    @Volatile var activity: Activity? = null
    fun setActivityInstance(newActivity: Activity?) { activity = newActivity }

    fun showToast(message: String?, duration: Int? = null) {
        val msg = message ?: return
        val ctx = activity ?: CloudStreamApp.getContext() ?: return
        val len = if (duration == Toast.LENGTH_LONG) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Handler(Looper.getMainLooper()).post { runCatching { Toast.makeText(ctx, msg, len).show() } }
    }
    fun showToast(act: Activity?, message: String?, duration: Int? = null) = showToast(message, duration)
}

/** Older plugins reach the app context and DataStore through this class. */
class AcraApplication {
    companion object {
        val context: Context? get() = CloudStreamApp.getContext()
        fun <T> setKey(path: String, value: T) = CloudStreamApp.setKey(path, value)
        fun <T> setKey(folder: String, path: String, value: T) = CloudStreamApp.setKey(DataStore.getFolderName(folder, path), value)
        fun <T> getKey(path: String): T? = CloudStreamApp.getKey(path)
        fun <T> getKey(path: String, defVal: T?): T? = CloudStreamApp.getKey(path, defVal)
        fun removeKey(path: String) = CloudStreamApp.removeKey(path)
    }
}

/** Lifecycle events plugins may subscribe to. Inert here (never fired); providers register in load(). */
class MainActivity {
    companion object {
        val afterPluginsLoadedEvent = Event<Boolean>()
        val mainPluginsLoadedEvent = Event<Boolean>()
        val afterRepositoryLoadedEvent = Event<Boolean>()
        val bookmarksUpdatedEvent = Event<Boolean>()
        val reloadHomeEvent = Event<Boolean>()
        val reloadLibraryEvent = Event<Boolean>()
        val reloadAccountEvent = Event<Boolean>()
    }
}
