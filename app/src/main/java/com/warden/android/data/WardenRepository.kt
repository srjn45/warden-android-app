package com.warden.android.data

import com.warden.android.data.model.DeleteRequest
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.RemoveWorktreeRequest
import com.warden.android.data.model.RoleInfo
import com.warden.android.data.model.Session
import com.warden.android.data.model.SessionList
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.Verdict
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import retrofit2.Response
import java.io.IOException

/** Outcome of the Connect screen's "Test connection" probe. */
sealed interface ConnectionResult {
    /** `/healthz` 200 and authed `/sessions` 200. [count] agents currently live. */
    data class Success(val count: Int) : ConnectionResult

    /** Reached the daemon but the token was rejected (401/403). */
    data object Unauthorized : ConnectionResult

    /** Reached the daemon but got an unexpected HTTP status. */
    data class HttpError(val code: Int, val where: String) : ConnectionResult

    /** Could not reach the daemon at all (DNS/connect/timeout/TLS). */
    data class Unreachable(val message: String) : ConnectionResult
}

/**
 * Single source of truth for the active connection and its transport. Holds the
 * live [WardenClient], exposes the read-only REST + SSE surface, and runs the
 * Connect-screen probe. Backed by the Keystore-encrypted [ConnectionStore].
 */
class WardenRepository(val store: ConnectionStore) {

    @Volatile
    private var client: WardenClient? = null

    @Volatile
    var active: Connection? = store.active()
        private set

    init {
        active?.let { client = WardenClient(it) }
    }

    /** Persists [connection], makes it active, and rebuilds the client. */
    fun activate(connection: Connection) {
        store.upsertAndActivate(connection)
        active = connection
        client = WardenClient(connection)
    }

    /**
     * Probes a candidate connection without persisting it: public `/healthz`
     * first (proves reachability), then authed `GET /sessions` (proves the
     * token). Distinguishes unreachable / bad-token / good so the UI can guide
     * the user precisely.
     */
    suspend fun testConnection(connection: Connection): ConnectionResult {
        val probe = WardenClient(connection)
        // Step 1: reachability via the public liveness probe.
        val health = try {
            probe.api.health()
        } catch (e: IOException) {
            return ConnectionResult.Unreachable(e.message ?: "network error")
        } catch (e: IllegalArgumentException) {
            return ConnectionResult.Unreachable("invalid host address")
        }
        if (!health.isSuccessful) {
            return ConnectionResult.HttpError(health.code(), "healthz")
        }

        // Step 2: token validity via the authenticated fleet snapshot.
        val sessions = try {
            probe.api.listSessions()
        } catch (e: IOException) {
            return ConnectionResult.Unreachable(e.message ?: "network error")
        }
        return when {
            sessions.isSuccessful ->
                ConnectionResult.Success(sessions.body()?.sessions?.size ?: 0)
            sessions.code() == 401 || sessions.code() == 403 ->
                ConnectionResult.Unauthorized
            else -> ConnectionResult.HttpError(sessions.code(), "sessions")
        }
    }

    /** One-shot fleet snapshot from the active connection (pull-to-refresh). */
    suspend fun listSessions(): Result<List<Session>> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.listSessions()
            if (resp.isSuccessful) {
                Result.success(resp.body()?.sessions ?: emptyList())
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Live fleet stream from the active connection. */
    fun sessionStream(): Flow<SessionList> {
        val c = client ?: error("no active connection")
        return c.sessionStream()
    }

    /** Opens a terminal attach socket for [sessionId] on the active connection. */
    fun openTerminal(sessionId: String, listener: TerminalListener): TerminalTransport {
        val c = client ?: error("no active connection")
        return c.openTerminal(sessionId, listener)
    }

    /** Built-in agent roles for the spawn-sheet picker. */
    suspend fun listRoles(): Result<List<RoleInfo>> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.listRoles()
            if (resp.isSuccessful) {
                Result.success(resp.body()?.roles ?: emptyList())
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Lists immediate subdirectories of [path] (null/blank = home) for the browser. */
    suspend fun listDirs(path: String?): Result<DirListing> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.listDirs(path?.ifBlank { null })
            if (resp.isSuccessful) {
                Result.success(resp.body() ?: DirListing())
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Spawns an agent. Maps the daemon's three outcomes to [SpawnOutcome]:
     * 201 → [SpawnOutcome.Created]; 428 (spawn-gate warning) →
     * [SpawnOutcome.NeedsConfirmation] carrying the [Verdict] to show in the
     * "spawn anyway?" prompt; anything else (400, network) → [SpawnOutcome.Failed]
     * with a message. Re-submit with `req.copy(force = true)` to clear a 428.
     */
    suspend fun spawn(req: SpawnRequest): SpawnOutcome {
        val c = client ?: return SpawnOutcome.Failed("No active connection")
        return try {
            val resp = c.api.spawn(req)
            when {
                resp.isSuccessful -> {
                    val session = resp.body()
                        ?: return SpawnOutcome.Failed("Spawn returned an empty body")
                    SpawnOutcome.Created(session)
                }
                resp.code() == 428 -> {
                    val conf = resp.errorBody()?.string()?.let { raw ->
                        runCatching { WardenJson.decodeFromString<com.warden.android.data.model.ConfirmationResponse>(raw) }
                            .getOrNull()
                    }
                    SpawnOutcome.NeedsConfirmation(conf?.verdict ?: Verdict())
                }
                else -> SpawnOutcome.Failed(errorMessage(resp))
            }
        } catch (e: IOException) {
            SpawnOutcome.Failed(e.message ?: "Network error")
        }
    }

    /** Kills an agent's tmux session but keeps its record + worktree. */
    suspend fun terminateAgent(id: String): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.terminate(id)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(HttpStatusException(resp.code()))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Deletes an agent: terminate (best-effort — a 404 or already-dead agent is
     * fine) then delete the record, optionally removing the worktree first. The
     * order matters: killing the tmux session before dropping the record avoids
     * orphaning a live process. Returns any non-blank server warning (e.g. "the
     * agent may still be live") so the UI can surface it.
     */
    suspend fun deleteAgent(id: String, removeWorktree: Boolean): Result<String?> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            // Best-effort terminate; ignore its status (404 = already gone).
            runCatching { c.api.terminate(id) }
            if (removeWorktree) {
                runCatching { c.api.removeWorktree(id, RemoveWorktreeRequest(force = true)) }
            }
            val resp = c.api.delete(id, DeleteRequest())
            if (resp.isSuccessful) {
                Result.success(resp.body()?.warning?.ifBlank { null })
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Extracts the daemon's `{error}` message from a failed response, else HTTP code. */
    private fun errorMessage(resp: Response<*>): String {
        val raw = runCatching { resp.errorBody()?.string() }.getOrNull()
        val parsed = raw?.let {
            runCatching { WardenJson.decodeFromString<ErrorBody>(it) }.getOrNull()
        }
        return parsed?.error?.ifBlank { null } ?: "HTTP ${resp.code()}"
    }

    @Serializable
    private data class ErrorBody(val error: String = "")

    class HttpStatusException(val code: Int) : Exception("HTTP $code")
}

/** Result of a spawn attempt (see [WardenRepository.spawn]). */
sealed interface SpawnOutcome {
    /** 201 — the agent was created. */
    data class Created(val session: Session) : SpawnOutcome

    /** 428 — the memory-pressure gate wants confirmation; resend with force. */
    data class NeedsConfirmation(val verdict: Verdict) : SpawnOutcome

    /** 400 / network / empty body — [message] is user-facing. */
    data class Failed(val message: String) : SpawnOutcome
}
