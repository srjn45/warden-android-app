package com.warden.android.data.model

import kotlinx.serialization.Serializable

/** Response body of the public `GET /healthz` liveness probe. */
@Serializable
data class HealthResponse(
    val status: String = "",
)
