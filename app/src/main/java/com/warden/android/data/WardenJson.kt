package com.warden.android.data

import kotlinx.serialization.json.Json

/**
 * Shared JSON configuration. `ignoreUnknownKeys` is essential: the daemon's
 * Session schema carries more fields than the app models (e.g. `events`,
 * `context_checked_at`), and new ones get added over time — the app must decode
 * forward-compatibly rather than reject the payload.
 */
val WardenJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}
