package com.aeriotv.android.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.hiddenTitlesDataStore: DataStore<Preferences> by preferencesDataStore(name = "aerio_hidden_titles")

/**
 * Titles the user long-pressed and hid. A hidden movie or series is gone
 * from every on-demand list, shelf and search result until it is unhidden
 * from the "Hidden" category. Scoped per playlist exactly like
 * [WatchlistStore] (the server host, not the local playlist row id) and kept
 * as one JSON blob in its own DataStore like the other VOD stores.
 */
@Singleton
class HiddenTitlesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Serializable
    data class Entry(
        /** "m:<movie uuid>" or "s:<series id>", the MediaItem key. */
        val key: String,
        val playlistId: String? = null,
        val hiddenAt: Long = System.currentTimeMillis(),
    )

    private val store get() = context.hiddenTitlesDataStore

    companion object {
        private val KEY = stringPreferencesKey("entries")
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The hidden keys for a playlist; rows with no scope match every playlist. */
    fun observe(playlistId: String?): Flow<Set<String>> = store.data.map { prefs ->
        decode(prefs[KEY])
            .filter { playlistId == null || it.playlistId == null || it.playlistId == playlistId }
            .mapTo(HashSet()) { it.key }
    }

    suspend fun hide(key: String, playlistId: String?) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            if (current.any { it.key == key && it.playlistId == playlistId }) return@edit
            prefs[KEY] = json.encodeToString(current + Entry(key = key, playlistId = playlistId))
        }
    }

    suspend fun unhide(key: String, playlistId: String?) {
        store.edit { prefs ->
            val current = decode(prefs[KEY])
            val next = current.filterNot { it.key == key && (it.playlistId == playlistId || it.playlistId == null) }
            if (next.size == current.size) return@edit
            prefs[KEY] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<Entry> =
        raw?.let { runCatching { json.decodeFromString<List<Entry>>(it) }.getOrNull() } ?: emptyList()
}
