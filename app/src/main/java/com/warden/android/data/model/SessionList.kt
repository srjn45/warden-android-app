package com.warden.android.data.model

import kotlinx.serialization.Serializable

/** Response body of `GET /api/v1/sessions` and each SSE snapshot frame. */
@Serializable
data class SessionList(
    val sessions: List<Session> = emptyList(),
)
