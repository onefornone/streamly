package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * CloudStream's `library` module ships BasePlugin but NOT the app-module `Plugin` class, yet most .cs3
 * plugins extend `Plugin`. Without this stand-in they die with NoClassDefFoundError. Zangetsu ships the same
 * workaround (android/.../cloudstream/CloudStreamPluginBase.kt). The package name MUST stay identical.
 */
abstract class Plugin : BasePlugin() {
    // No hand-written `$stable` here: the Compose compiler plugin generates it on this class, and a second one is a JVM clash.
    open fun load(context: Context) { load() }          // real Plugin.load(Context) delegates to load()
    var resources: Resources? = null
    var needsResources: Boolean = false
    var openSettings: ((context: Context) -> Unit)? = null
}

/** Some plugins call PluginManager.* from reload(); report "nothing installed" instead of crashing. */
data class PluginData(val internalName: String, val isOnline: Boolean = false, val filePath: String = "", val url: String? = null, val version: Int = 1)

object PluginManager {
    fun getPluginsOnline(): Array<PluginData> = emptyArray()
    fun getPluginsLocal(): Array<PluginData> = emptyArray()
    fun unloadPlugin(absolutePath: String) {}
}
