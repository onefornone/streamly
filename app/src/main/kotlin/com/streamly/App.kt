package com.streamly

import android.app.Application
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.streamly.data.WatchStore
import com.streamly.metadata.MetadataRepository
import com.streamly.plugins.PluginManager
import com.streamly.sources.ArchiveProvider

class App : Application() {
    lateinit var plugins: PluginManager; private set
    lateinit var meta: MetadataRepository; private set
    lateinit var watch: WatchStore; private set

    override fun onCreate() {
        super.onCreate()
        CloudStreamApp.setContext(this)        // plugins read the app context + settings store through this
        plugins = PluginManager(this)
        meta = MetadataRepository(this)
        watch = WatchStore(this)
        APIHolder.allProviders.add(ArchiveProvider().also { it.sourcePlugin = "builtin" })   // works with zero plugins installed
        Thread { plugins.loadAll() }.start()   // re-register installed plugins at startup
    }
}
