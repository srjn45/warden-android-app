package com.warden.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A warden agent session, mirroring the daemon's `Session` schema
 * (`internal/daemon/apidocs/openapi.yaml`). Only `id`, `type`, `status`,
 * `created_at`, `updated_at` are required by the spec; everything else may be
 * absent, so every other field carries a default. Unknown fields are ignored by
 * the [com.warden.android.data.WardenJson] configuration, so new daemon fields
 * never break decoding.
 *
 * `status`, `type`, and `context_state` are kept as plain strings rather than
 * enums on purpose: the daemon can emit values outside the documented sets
 * (e.g. an empty `type` for free-form agents), and a strict enum would reject
 * the whole payload. See [Status] and [ContextState] for the known values.
 */
@Serializable
data class Session(
    val id: String,
    val name: String = "",
    val type: String = "",
    val ticket: String = "",
    @SerialName("tmux_session") val tmuxSession: String = "",
    @SerialName("claude_session_id") val claudeSessionId: String = "",
    val repo: String = "",
    val worktree: String = "",
    val branch: String = "",
    val pr: String = "",
    val prompt: String = "",
    val workdir: String = "",
    val subject: String = "",
    val tags: List<String> = emptyList(),
    val status: String = "",
    val pid: Int = 0,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("last_pane_excerpt") val lastPaneExcerpt: String = "",
    val role: String = "",
    @SerialName("pipeline_id") val pipelineId: String = "",
    @SerialName("job_id") val jobId: String = "",
    val model: String = "",
    @SerialName("context_tokens") val contextTokens: Int = 0,
    @SerialName("context_state") val contextState: String = "",
) {
    /** Human-facing title for a list row: the agent name, falling back to id. */
    val displayName: String get() = name.ifBlank { id }
}

/** Known [Session.status] values (openapi `Status` enum). */
object Status {
    const val SPAWNING = "spawning"
    const val WORKING = "working"
    const val WAITING_FOR_INPUT = "waiting_for_input"
    const val IDLE = "idle"
    const val DONE = "done"
    const val ERRORED = "errored"
    const val ORPHANED = "orphaned"
    const val RATE_LIMITED = "rate_limited"
}

/** Known [Session.contextState] values (openapi `context_state` enum). */
object ContextState {
    const val OK = "ok"
    const val WARNING = "warning"
    const val CRITICAL = "critical"
}
