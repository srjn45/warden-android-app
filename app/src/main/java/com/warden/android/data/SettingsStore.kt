package com.warden.android.data

import android.content.Context

/**
 * Small store for non-secret UI preferences, backed by a plain
 * [android.content.SharedPreferences]. These are display choices (not
 * credentials), so unlike [ConnectionStore] they don't need Keystore encryption.
 * The web cockpit keeps the equivalent under `localStorage['warden.grouping']`.
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("warden_ui", Context.MODE_PRIVATE)

    /** Persisted agent-list group-by mode id (see the agents' GroupMode). */
    var groupModeId: String?
        get() = prefs.getString(KEY_GROUP, null)
        set(value) = prefs.edit().putString(KEY_GROUP, value).apply()

    private companion object {
        const val KEY_GROUP = "grouping"
    }
}
