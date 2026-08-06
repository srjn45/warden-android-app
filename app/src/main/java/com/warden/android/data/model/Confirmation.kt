package com.warden.android.data.model

import kotlinx.serialization.Serializable

/**
 * `428 Precondition Required` body from `POST /api/v1/spawn` when the daemon's
 * memory-pressure spawn gate warns. Re-submit the same request with
 * `force = true` to proceed. Mirrors the openapi `ConfirmationResponse`.
 */
@Serializable
data class ConfirmationResponse(
    val confirmation_required: Boolean = true,
    val verdict: Verdict = Verdict(),
)

/**
 * The spawn gate's assessment (openapi `Verdict`). [reason] is a human-readable
 * explanation to surface in the "spawn anyway?" prompt; the counts give context.
 */
@Serializable
data class Verdict(
    val elevated: Boolean = false,
    val level: Int = 0,
    val agent_count: Int = 0,
    val max_agents: Int = 0,
    val reason: String = "",
)
