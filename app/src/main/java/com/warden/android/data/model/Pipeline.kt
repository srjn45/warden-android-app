package com.warden.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A warden pipeline — a DAG of agent jobs — mirroring the daemon's `Pipeline`
 * schema (`internal/daemon/apidocs/openapi.yaml`). Every field carries a default
 * so a sparse payload never fails to decode, and unknown fields are ignored by
 * [com.warden.android.data.WardenJson], so new daemon fields don't break the app.
 *
 * `status` is kept a plain string (not an enum) on purpose: the daemon may emit
 * values outside the documented set, and a strict enum would reject the whole
 * payload. See [PipelineStatus] for the known values.
 */
@Serializable
data class Pipeline(
    val id: String = "",
    val name: String = "",
    val repo: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
    val jobs: List<PipelineJob> = emptyList(),
) {
    /** Human-facing title for a list row: the pipeline name, falling back to id. */
    val displayName: String get() = name.ifBlank { id }

    /** How many jobs have settled (done/failed/skipped) out of the total. */
    val doneCount: Int get() = jobs.count { it.status in JobStatus.SETTLED }
}

/**
 * One job in a [Pipeline]'s DAG. [dependsOn] names the upstream job ids that must
 * settle before this one runs; [sessionId], when set, is the live agent session
 * backing the job — the detail screen opens its terminal.
 */
@Serializable
data class PipelineJob(
    val id: String = "",
    val prompt: String = "",
    @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
    val handoff: String = "",
    val worktree: String = "",
    val supervised: Boolean = false,
    val type: String = "",
    @SerialName("run_if") val runIf: String = "",
    @SerialName("session_id") val sessionId: String = "",
    val status: String = "",
    val output: String = "",
    val branch: String = "",
)

/** Response body of `GET /api/v1/pipelines`. */
@Serializable
data class PipelineList(
    val pipelines: List<Pipeline> = emptyList(),
)

/** Known [Pipeline.status] values (`internal/pipeline` `Status`). */
object PipelineStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELED = "canceled"
}

/** Known [PipelineJob.status] values (`internal/pipeline` `JobStatus`). */
object JobStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val DONE = "done"
    const val FAILED = "failed"
    const val SKIPPED = "skipped"
    const val NEEDS_ATTENTION = "needs_attention"

    /** Terminal states — a job here will not run again on its own. */
    val SETTLED = setOf(DONE, FAILED, SKIPPED)
}
