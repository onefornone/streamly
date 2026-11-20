package com.streamly.plugins

import android.content.Context
import org.json.JSONArray

/** Repository / plugin-list links the USER has added, kept so they survive a restart. Ships empty. */
class RepoStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("repos", Context.MODE_PRIVATE)

    fun list(): List<String> = JSONArray(prefs.getString("urls", "[]")).let { a -> (0 until a.length()).map(a::getString) }
    fun add(url: String) { val l = list(); if (url !in l) save(l + url) }
    fun remove(url: String) = save(list() - url)
    private fun save(l: List<String>) { prefs.edit().putString("urls", JSONArray(l).toString()).apply() }
}
