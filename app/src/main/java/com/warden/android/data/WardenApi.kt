package com.warden.android.data

import com.warden.android.data.model.HealthResponse
import com.warden.android.data.model.SessionList
import retrofit2.Response
import retrofit2.http.GET

/**
 * Retrofit surface for the read-only REST endpoints used in P0. The bearer
 * token is attached by [BearerInterceptor], so no per-call auth arguments are
 * needed here. Spawn/input/terminate land in later phases.
 */
interface WardenApi {

    /** Public liveness probe. Returns 200 + `{"status":"ok"}` when healthy. */
    @GET("healthz")
    suspend fun health(): Response<HealthResponse>

    /** Authenticated fleet snapshot. 200 + `{sessions:[…]}`; 401 on bad token. */
    @GET("api/v1/sessions")
    suspend fun listSessions(): Response<SessionList>
}
