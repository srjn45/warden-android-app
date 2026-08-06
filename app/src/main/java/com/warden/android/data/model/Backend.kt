package com.warden.android.data.model

/**
 * An agent backend for the spawn-sheet picker: the wire [id] sent as
 * `SpawnRequest.backend` and a human [label].
 *
 * The daemon has no `/backends` endpoint, so this list is a static mirror of its
 * backend registry (the `internal/agentbackend/backends` package — each
 * backend's `ID()` and `DisplayName()`). It can drift if warden adds a backend;
 * a future `GET /api/v1/backends` would make it self-updating. [DEFAULT]
 * ("claude") is what the daemon assumes for an empty backend, so it leads the list.
 */
data class Backend(val id: String, val label: String) {
    companion object {
        val DEFAULT = Backend("claude", "Claude Code")

        /** All known backends, default first, then alphabetical by label. */
        val ALL: List<Backend> = listOf(
            DEFAULT,
            Backend("aider", "Aider"),
            Backend("antigravity", "Antigravity"),
            Backend("codex", "Codex"),
            Backend("crush", "Crush"),
            Backend("cursor", "Cursor"),
            Backend("goose", "Goose"),
            Backend("opencode", "OpenCode"),
        )
    }
}
