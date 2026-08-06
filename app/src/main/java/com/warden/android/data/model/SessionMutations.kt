package com.warden.android.data.model

import kotlinx.serialization.Serializable

/** Generic `{status}` body returned by the OK responses (terminate, …). */
@Serializable
data class StatusResponse(val status: String = "")

/** Body for `POST /api/v1/sessions/{id}/delete`. [hard] forces record removal. */
@Serializable
data class DeleteRequest(val hard: Boolean = false)

/**
 * `POST …/delete` result. [warning] is set (non-blank) when the agent may still
 * be live — the app surfaces it rather than treating the delete as fully clean.
 */
@Serializable
data class DeleteResponse(val status: String = "", val warning: String = "")

/** Body for `POST /api/v1/sessions/{id}/remove-worktree`. */
@Serializable
data class RemoveWorktreeRequest(
    val force: Boolean = false,
    val delete_adopted_branch: Boolean = false,
)
