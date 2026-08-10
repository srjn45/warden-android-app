package com.warden.android.data.model

import kotlinx.serialization.Serializable

/**
 * Body for `POST /api/v1/spawn`, mirroring the daemon's `SpawnRequest` schema.
 * Every field is optional; the app sends a free-form spawn (empty [type]) with
 * whatever the create sheet filled in.
 *
 * Serialized by [com.warden.android.data.WardenJson], which does NOT encode
 * defaults — so blank strings and `false` booleans are omitted from the wire
 * payload and the daemon applies its own defaults (e.g. empty [backend] = claude,
 * absent [cwd] = the user's home). Set [force] `true` only when re-submitting
 * after a `428` spawn-gate warning.
 */
@Serializable
data class SpawnRequest(
    val type: String = "",
    /**
     * Session kind. Empty spawns an AI agent (the daemon's default); `"terminal"`
     * opens a free-form shell pane, in which case [backend], [model], [role],
     * [prompt], and [type] are ignored server-side (only [cwd] + [name] matter).
     */
    val kind: String = "",
    val name: String = "",
    val repo: String = "",
    val prompt: String = "",
    val cwd: String = "",
    val model: String = "",
    val backend: String = "",
    val role: String = "",
    val worktree: Boolean = false,
    val force: Boolean = false,
    val tags: List<String> = emptyList(),
)
