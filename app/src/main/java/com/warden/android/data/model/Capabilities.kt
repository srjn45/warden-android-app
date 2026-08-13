package com.warden.android.data.model

import kotlinx.serialization.Serializable

/**
 * Body of `GET /api/v1/capabilities` — the daemon's self-describing feature
 * flags. The app keys optional behaviour on the presence of a flag string rather
 * than parsing a version number, so a single check works across releases.
 *
 * Added alongside the terminal-sessions release; **404** on daemons that predate
 * it (the caller then falls back to a `/backends` probe). Unknown flags are
 * ignored, so new daemon capabilities never break decoding.
 */
@Serializable
data class Capabilities(val capabilities: List<String> = emptyList())

/** Known [Capabilities] flag strings the app looks for. */
object Capability {
    /**
     * The daemon models terminals as first-class sessions (`kind = "terminal"`,
     * created via `POST /spawn` with `kind`), not as an agent backend. When
     * present, the app shows a dedicated Terminals section + "New terminal"
     * action; when absent it treats `terminal` as a legacy backend.
     */
    const val TERMINAL_SESSIONS = "terminal-sessions"

    /**
     * The daemon can fire agents (and pipelines) on a schedule and exposes the
     * `GET /schedules` control surface. When present, the app shows a dedicated
     * Scheduled section. Runs it fires carry `schedule_id` on their [Session], so
     * they surface there and stay out of the Agents list. If the scheduler is
     * configured off, the schedule routes answer **403** and the section shows a
     * "scheduling disabled" empty state.
     */
    const val SCHEDULED_AGENTS = "scheduled-agents"
}
