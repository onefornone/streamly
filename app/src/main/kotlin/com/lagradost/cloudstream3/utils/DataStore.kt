package com.lagradost.cloudstream3.utils

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Stand-in for CloudStream's app-module DataStore (key/value settings in SharedPreferences, values as JSON).
 * The CloudStream `library` artifact doesn't include it, but plugins use it to persist their settings:
 * older plugins inline getKey/setKey and only need getMapper()/getSharedPrefs(); newer ones call the
 * Context.getKey/setKey extensions. Both shapes are provided and share one prefs file.
 * The package and member names must match what plugins were compiled against.
 */
object DataStore {
    private const val PREFS = "cs3_datastore"

    val mapper: JsonMapper = JsonMapper.builder().addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build()

    fun getSharedPrefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun getFolderName(folder: String, path: String): String = "$folder/$path"

    // ---- app-context versions (used by CloudStreamApp / AcraApplication) ----
    fun <T> setKey(path: String, value: T) { CloudStreamApp.getContext()?.setKey(path, value) }
    fun <T> getKey(path: String, valueType: Class<T>): T? {
        val ctx = CloudStreamApp.getContext() ?: return null
        val json = getSharedPrefs(ctx).getString(path, null) ?: return null
        return runCatching { mapper.readValue(json, valueType) }.getOrNull()
    }

    // ---- Context extensions (member extensions, same shape as upstream) ----
    fun <T> Context.setKey(path: String, value: T) {
        runCatching { getSharedPrefs(this).edit().putString(path, mapper.writeValueAsString(value)).apply() }
    }
    fun <T> Context.setKey(folder: String, path: String, value: T) = setKey(getFolderName(folder, path), value)

    fun <T : Any> Context.getKey(path: String, valueType: Class<T>): T? {
        val json = getSharedPrefs(this).getString(path, null) ?: return null
        return runCatching { mapper.readValue(json, valueType) }.getOrNull()
    }
    fun <T : Any> Context.getKey(folder: String, path: String, valueType: Class<T>): T? =
        getKey(getFolderName(folder, path), valueType)

    fun Context.getKeys(folder: String): List<String> {
        val prefix = folder.trimEnd('/') + "/"
        return getSharedPrefs(this).all.keys.filter { it.startsWith(prefix) }
    }
    fun Context.containsKey(path: String): Boolean = getSharedPrefs(this).contains(path)
    fun Context.containsKey(folder: String, path: String): Boolean = containsKey(getFolderName(folder, path))
    fun Context.removeKey(path: String) { getSharedPrefs(this).edit().remove(path).apply() }
    fun Context.removeKey(folder: String, path: String) = removeKey(getFolderName(folder, path))
    fun Context.removeKeys(folder: String): Int {
        val keys = getKeys(folder)
        val e = getSharedPrefs(this).edit(); keys.forEach { e.remove(it) }; e.apply()
        return keys.size
    }
}

/** Stand-in for CloudStream's app-module Event (plugins subscribe to app lifecycle events through it). */
class Event<T> {
    private val observers = mutableSetOf<(T) -> Unit>()
    val size: Int get() = observers.size
    operator fun plusAssign(observer: (T) -> Unit) { synchronized(observers) { observers.add(observer) } }
    operator fun minusAssign(observer: (T) -> Unit) { synchronized(observers) { observers.remove(observer) } }
    operator fun invoke(value: T) { synchronized(observers) { observers.toList() }.forEach { it(value) } }
}
