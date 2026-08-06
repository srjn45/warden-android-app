package com.warden.android.data.model

import kotlinx.serialization.Serializable

/**
 * A backend as reported by `GET /api/v1/backends` (warden ≥ v8.16.7). The wire
 * shape is `{ id, display_name, default, available }` with all four fields
 * always present; the daemon orders them default-first then alphabetical by id.
 *
 * [available] reflects an `exec.LookPath` install check on the daemon host, so
 * the picker can grey out backends whose binary isn't installed. It defaults to
 * `true` so an older daemon or a payload missing the field never hides a backend.
 */
@Serializable
data class BackendInfo(
    val id: String = "",
    val display_name: String = "",
    val default: Boolean = false,
    val available: Boolean = true,
) {
    /** Human label, falling back to this build's static name (then the raw id). */
    val label: String get() = display_name.ifBlank { Backend.labelFor(id) }

    /** The picker's lighter [Backend] currency (wire id + display label). */
    fun toBackend(): Backend = Backend(id, label)
}

/** `GET /api/v1/backends` body. Empty list = treat as unavailable → static fallback. */
@Serializable
data class BackendsResponse(val backends: List<BackendInfo> = emptyList())
