package com.warden.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A daemon **schedule** (warden ≥ the scheduled-agents release): a saved timer
 * that fires an agent spawn — or a whole pipeline — on a cadence. Mirrors the
 * daemon's schedule schema; unknown fields are ignored by
 * [com.warden.android.data.WardenJson], so new daemon fields never break decoding.
 *
 * Two fields are easy to misread (the names come straight from the daemon):
 *  - [kind] is the **cadence** — `"cron"` (recurring) or `"at"` (single-shot).
 *  - [mode] is the **fire type** — `"agent"` (spawn one agent) or `"pipeline"`.
 *
 * So the "what does it run" distinction keys off [mode], NOT [kind].
 *
 * When a run fires it spawns a [Session] carrying `schedule_id` (see
 * [Session.scheduleId]); the app finds that live session on the fleet stream to
 * offer "running now → open the PTY". [lastRunSessionId] is the durable
 * historical pointer and is intentionally not used for the live-attach case.
 */
@Serializable
data class Schedule(
    val id: String,
    val name: String = "",
    /** Cadence: `"cron"` or `"at"`. See [ScheduleCadence]. */
    val kind: String = "",
    /** Fire type: `"agent"` or `"pipeline"`. See [ScheduleMode]. */
    val mode: String = "",
    val enabled: Boolean = true,
    /** 5-field cron spec (or `@daily` etc.) when [kind] == `"cron"`. */
    val cron: String = "",
    /** RFC3339 fire time when [kind] == `"at"` (single-shot). */
    val at: String = "",
    @SerialName("next_run") val nextRun: String = "",
    @SerialName("last_run_at") val lastRunAt: String = "",
    /** Session id of the most recent run (durable; may have rotated away). */
    @SerialName("last_run_session_id") val lastRunSessionId: String = "",
    @SerialName("last_run_status") val lastRunStatus: String = "",
    @SerialName("last_error") val lastError: String = "",
    // --- agent-spawn spec (mode == "agent") ---
    val type: String = "",
    val repo: String = "",
    val branch: String = "",
    val prompt: String = "",
    /** Optional fixed name for the agent the schedule spawns. */
    val agent: String = "",
) {
    /** Human-facing title for a list row: the schedule name, falling back to id. */
    val displayName: String get() = name.ifBlank { id }

    /** True for a recurring cron schedule (vs a one-shot `at`). */
    val isRecurring: Boolean get() = kind == ScheduleCadence.CRON

    /** True when the schedule fires a whole pipeline rather than a single agent. */
    val isPipeline: Boolean get() = mode == ScheduleMode.PIPELINE

    /** Cadence summary for the row: the cron expression, or the one-shot time. */
    val cadenceLabel: String get() = when {
        cron.isNotBlank() -> cron
        at.isNotBlank() -> at
        else -> kind
    }

    /** What the schedule runs: the agent prompt, or a pipeline marker. */
    val taskLabel: String get() = when {
        isPipeline -> "Runs a pipeline"
        prompt.isNotBlank() -> prompt
        agent.isNotBlank() -> agent
        else -> type
    }
}

/** Wrapper for `GET /api/v1/schedules`. */
@Serializable
data class ScheduleList(val schedules: List<Schedule> = emptyList())

/** Known [Schedule.kind] (cadence) values. */
object ScheduleCadence {
    const val CRON = "cron"
    const val AT = "at"
}

/** Known [Schedule.mode] (fire type) values. */
object ScheduleMode {
    const val AGENT = "agent"
    const val PIPELINE = "pipeline"
}
