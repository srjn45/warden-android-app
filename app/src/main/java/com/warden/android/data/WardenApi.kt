package com.warden.android.data

import com.warden.android.data.model.BackendsResponse
import com.warden.android.data.model.DeleteRequest
import com.warden.android.data.model.DeleteResponse
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.HealthResponse
import com.warden.android.data.model.RemoveWorktreeRequest
import com.warden.android.data.model.RolesResponse
import com.warden.android.data.model.Session
import com.warden.android.data.model.SessionList
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.StatusResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit surface for the REST endpoints the app uses. The bearer token is
 * attached by [BearerInterceptor], so no per-call auth arguments are needed.
 *
 * Reads are used by P0 (list) and P2 (roles/dirs pickers); the writes
 * ([spawn]/[terminate]/[delete]/[removeWorktree]) back P2 create + delete.
 */
interface WardenApi {

    /** Public liveness probe. Returns 200 + `{"status":"ok"}` when healthy. */
    @GET("healthz")
    suspend fun health(): Response<HealthResponse>

    /** Authenticated fleet snapshot. 200 + `{sessions:[…]}`; 401 on bad token. */
    @GET("api/v1/sessions")
    suspend fun listSessions(): Response<SessionList>

    /** Built-in agent roles for the spawn-sheet picker (general first). */
    @GET("api/v1/roles")
    suspend fun listRoles(): Response<RolesResponse>

    /**
     * Available agent backends for the spawn-sheet picker (default first, then
     * alphabetical by id). Added in warden v8.16.7; **404** on older daemons —
     * the caller then falls back to the static [com.warden.android.data.model.Backend] list.
     */
    @GET("api/v1/backends")
    suspend fun listBackends(): Response<BackendsResponse>

    /** Immediate subdirectories of [path] (empty = home) for the dir browser. */
    @GET("api/v1/fs/dirs")
    suspend fun listDirs(@Query("path") path: String?): Response<DirListing>

    /**
     * Spawn a new agent. 201 + [Session] on success; **428** + a
     * `ConfirmationResponse` body when the memory-pressure gate warns (re-submit
     * with `force = true`); 400 on an invalid request. The 428/400 bodies arrive
     * via `errorBody()` — the caller parses them.
     */
    @POST("api/v1/spawn")
    suspend fun spawn(@Body req: SpawnRequest): Response<Session>

    /** Kill an agent's tmux session (keeps the record + worktree). 200 / 404. */
    @POST("api/v1/sessions/{id}/terminate")
    suspend fun terminate(@Path("id") id: String): Response<StatusResponse>

    /** Delete a session record. [DeleteResponse.warning] set if it may be live. */
    @POST("api/v1/sessions/{id}/delete")
    suspend fun delete(
        @Path("id") id: String,
        @Body req: DeleteRequest,
    ): Response<DeleteResponse>

    /** Remove the session's git worktree + branch. 200 / 404. */
    @POST("api/v1/sessions/{id}/remove-worktree")
    suspend fun removeWorktree(
        @Path("id") id: String,
        @Body req: RemoveWorktreeRequest,
    ): Response<StatusResponse>
}
