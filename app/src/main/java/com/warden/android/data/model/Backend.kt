package com.warden.android.data.model

/**
 * An agent backend for the spawn-sheet picker: the wire [id] sent as
 * `SpawnRequest.backend` and a human [label].
 *
 * This list is a static mirror of the daemon's backend registry (the
 * `internal/agentbackend/backends` package — each backend's `ID()` and
 * `DisplayName()`, including the plain-shell `terminal` backend). At runtime the
 * create sheet prefers the live `GET /api/v1/backends` endpoint (warden ≥
 * v8.16.7) and only falls back to this list (via [staticInfos]) when the daemon
 * is older (404) or unreachable — so drift only shows on legacy daemons.
 * [DEFAULT] ("claude") is what the daemon assumes for an empty backend, so it
 * leads the list.
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
            Backend("terminal", "Terminal (plain shell)"),
        )

        /**
         * The static list as [BackendInfo]s, for when the live `/backends`
         * endpoint is unavailable. Availability is unknown offline, so every
         * entry is marked available (never grey out on a guess); the [DEFAULT]
         * entry carries the `default` flag.
         */
        fun staticInfos(): List<BackendInfo> = ALL.map {
            BackendInfo(id = it.id, display_name = it.label, default = it == DEFAULT, available = true)
        }

        /**
         * Display label for a backend [id] as it appears on a session. Falls back
         * to the raw id for backends this build doesn't know, and treats a blank
         * id as [DEFAULT] (the daemon's default for an empty backend).
         */
        fun labelFor(id: String): String {
            val key = id.ifBlank { DEFAULT.id }
            return ALL.firstOrNull { it.id == key }?.label ?: key
        }
    }
}
