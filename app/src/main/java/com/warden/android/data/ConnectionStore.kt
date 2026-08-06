package com.warden.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Persists saved [Connection]s (host + bearer token) in a Keystore-backed
 * [EncryptedSharedPreferences] file, so tokens are encrypted at rest
 * (design.md §4). Written multi-host-ready: a list of connections plus the id
 * of the currently-active one. P0 UI only exercises a single connection.
 *
 * All values are stored as a single encrypted JSON blob under one key.
 */
class ConnectionStore(context: Context) {

    @Serializable
    private data class Persisted(
        val connections: List<Connection> = emptyList(),
        val activeLabel: String? = null,
    )

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun load(): Persisted {
        val raw = prefs.getString(KEY_STATE, null) ?: return Persisted()
        return runCatching { WardenJson.decodeFromString<Persisted>(raw) }.getOrDefault(Persisted())
    }

    private fun save(state: Persisted) {
        prefs.edit().putString(KEY_STATE, WardenJson.encodeToString(state)).apply()
    }

    /** All saved connections, in insertion order. */
    fun connections(): List<Connection> = load().connections

    /** The currently active connection, or null if none saved/selected. */
    fun active(): Connection? {
        val state = load()
        val label = state.activeLabel ?: return state.connections.firstOrNull()
        return state.connections.firstOrNull { it.label == label }
            ?: state.connections.firstOrNull()
    }

    /**
     * Upserts a connection (keyed by [Connection.label]) and marks it active.
     * Re-saving the same label replaces host/token — the common "edit and
     * re-test" flow.
     */
    fun upsertAndActivate(connection: Connection) {
        val state = load()
        val others = state.connections.filterNot { it.label == connection.label }
        save(Persisted(connections = others + connection, activeLabel = connection.label))
    }

    /** Marks an already-saved connection active by [label] (no-op if unknown). */
    fun setActive(label: String) {
        val state = load()
        if (state.connections.none { it.label == label }) return
        save(state.copy(activeLabel = label))
    }

    /** Removes a connection by label; clears active if it was the one removed. */
    fun remove(label: String) {
        val state = load()
        val remaining = state.connections.filterNot { it.label == label }
        val active = if (state.activeLabel == label) remaining.firstOrNull()?.label else state.activeLabel
        save(Persisted(connections = remaining, activeLabel = active))
    }

    private companion object {
        const val PREFS_FILE = "warden_connections"
        const val KEY_STATE = "state"
    }
}
