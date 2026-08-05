package com.warden.android.data

import com.warden.android.data.model.Session
import com.warden.android.data.model.SessionList
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.flow.Flow
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

    class HttpStatusException(val code: Int) : Exception("HTTP $code")
}
