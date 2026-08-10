package com.warden.android.data

import com.warden.android.data.model.BackendInfo
import com.warden.android.data.model.Capability
import com.warden.android.data.model.DeleteRequest
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.Kind
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.RemoveWorktreeRequest
import com.warden.android.data.model.RoleInfo
import com.warden.android.data.model.Session
import com.warden.android.data.model.SessionList
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.Verdict
import com.warden.android.data.demo.DemoTransport
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
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

/** Reactive view of the saved hosts and which one is currently active. */
data class HostsState(
    val connections: List<Connection> = emptyList(),
    val activeLabel: String? = null,
)

/** Connection phase of the single live fleet stream. */
enum class StreamPhase { Connecting, Live, Disconnected }

/**
 * The active host's live fleet, as one shared snapshot. Both the Agents and the
 * Terminals screens derive from this same value (filtered by [Session.kind]), so
 * there is only ever ONE SSE stream open to the active host.
 */
data class FleetState(
    val sessions: List<Session> = emptyList(),
    val phase: StreamPhase = StreamPhase.Connecting,
    val error: String? = null,
)

/**
 * Single source of truth for the active connection and its transport. Holds the
 * live [WardenClient], exposes the read-only REST + SSE surface, and runs the
 * Connect-screen probe. Backed by the Keystore-encrypted [ConnectionStore].
 */
class WardenRepository(val store: ConnectionStore) {

    /** App-lifetime scope for the shared fleet stream + capability detection. */
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * The transport for the active host, as a flow so the shared [fleet] stream
     * re-targets whenever the host switches. A getter over its value keeps the
     * many `client ?: …` REST call sites unchanged.
     */
    private val _transport = MutableStateFlow<WardenTransport?>(null)
    private val client: WardenTransport? get() = _transport.value

    @Volatile
    var active: Connection? = store.active()
        private set

    /** True when the active host is the built-in offline demo (fixture-backed). */
    val isDemo: Boolean get() = active?.isDemo == true

    /**
     * Builds the transport for [connection]: fixture-backed [DemoTransport] for
     * the demo host, otherwise a real Retrofit/SSE [WardenClient].
     */
    private fun newTransport(connection: Connection): WardenTransport =
        if (connection.isDemo) DemoTransport() else WardenClient(connection)

    /**
     * Reactive snapshot of all saved hosts and which one is active, so the host
     * drawer + title-bar picker recompose as connections are added, switched, or
     * forgotten. Only the active host runs a live stream/attach at any moment;
     * switching is instant because every host's token is already saved.
     */
    private val _hosts = MutableStateFlow(HostsState(store.connections(), active?.label))
    val hosts: StateFlow<HostsState> = _hosts.asStateFlow()

    /**
     * Whether the active host models terminals as first-class sessions (the
     * `terminal-sessions` capability). Drives the Terminals section's visibility
     * and whether the create flow spawns `kind=terminal` vs a legacy backend.
     * Re-detected on every host switch.
     */
    private val _terminalSessions = MutableStateFlow(false)
    val terminalSessions: StateFlow<Boolean> = _terminalSessions.asStateFlow()

    init {
        active?.let { _transport.value = newTransport(it) }
        // Re-run capability detection whenever the active transport changes.
        _transport
            .onEach { t -> _terminalSessions.value = t?.let { detectTerminalSessions(it) } ?: false }
            .launchIn(scope)
    }

    /**
     * The single live fleet stream for the active host, shared across every
     * collector (Agents + Terminals). [flatMapLatest] tears down the old socket
     * and opens a new one on each host switch; the last snapshot is retained
     * across a transient disconnect so the list stays visible with an offline
     * badge. Kept warm for a few seconds after the last collector leaves so tab
     * switches don't churn the socket ([SharingStarted.WhileSubscribed]).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val fleet: StateFlow<FleetState> = _transport
        .flatMapLatest { t ->
            flow {
                emit(FleetEvent.Reconnecting)
                if (t != null) {
                    emitAll(t.sessionStream().map { FleetEvent.Snapshot(it.sessions) })
                }
            }.catch { e -> emit(FleetEvent.Failed(e.message)) }
        }
        .scan(FleetState()) { state, event ->
            when (event) {
                FleetEvent.Reconnecting -> FleetState(phase = StreamPhase.Connecting)
                is FleetEvent.Snapshot -> FleetState(event.sessions, StreamPhase.Live, null)
                is FleetEvent.Failed ->
                    state.copy(phase = StreamPhase.Disconnected, error = event.error)
            }
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), FleetState())

    /** Internal events feeding the [fleet] reducer. */
    private sealed interface FleetEvent {
        /** A new transport (host switch / manual reconnect): clear + Connecting. */
        data object Reconnecting : FleetEvent
        data class Snapshot(val sessions: List<Session>) : FleetEvent
        data class Failed(val error: String?) : FleetEvent
    }

    /**
     * Detects whether [t]'s daemon supports terminal-as-kind. Prefers the
     * explicit `GET /capabilities` flag; on a 404 (older daemon) or flag-absent
     * result falls back to the self-describing proxy — `terminal` MISSING from
     * `GET /backends` (the two changes ship in one atomic daemon release). Any
     * network failure resolves to `false` (safe: no Terminals section, and
     * `terminal` stays a legacy backend in the create sheet).
     */
    private suspend fun detectTerminalSessions(t: WardenTransport): Boolean = try {
        val caps = t.api.capabilities()
        if (caps.isSuccessful) {
            caps.body()?.capabilities?.contains(Capability.TERMINAL_SESSIONS) == true
        } else {
            terminalAbsentFromBackends(t)
        }
    } catch (e: IOException) {
        false
    }

    /** Fallback signal: a successful `/backends` that no longer lists `terminal`. */
    private suspend fun terminalAbsentFromBackends(t: WardenTransport): Boolean = try {
        val resp = t.api.listBackends()
        resp.isSuccessful && resp.body()?.backends?.none { it.id == "terminal" } == true
    } catch (e: IOException) {
        false
    }

    private fun refreshHosts() {
        _hosts.value = HostsState(store.connections(), active?.label)
    }

    /** Persists [connection], makes it active, and rebuilds the client. */
    fun activate(connection: Connection) {
        store.upsertAndActivate(connection)
        active = connection
        _transport.value = newTransport(connection)
        refreshHosts()
    }

    /**
     * Activates the built-in offline **demo** host: canned fixtures with no
     * network, so the app can be explored (and Play Store review can exercise it)
     * without a warden daemon. Persisted like any host, so it survives relaunch
     * until forgotten from the host drawer.
     */
    fun activateDemo() = activate(Connection.demo())

    /**
     * Switches the foreground host to an already-saved connection (by [label]).
     * Rebuilds the transport so the next stream/attach targets it; a no-op if the
     * label is unknown or already active.
     */
    fun switchTo(label: String) {
        val target = store.connections().firstOrNull { it.label == label } ?: return
        if (target.label == active?.label) return
        store.setActive(label)
        active = target
        _transport.value = newTransport(target)
        refreshHosts()
    }

    /**
     * Forgets a saved host, dropping its stored token. If it was active, falls
     * back to the next saved host (or none — the caller then returns to Connect).
     */
    fun disconnect(label: String) {
        store.remove(label)
        active = store.active()
        _transport.value = active?.let { newTransport(it) }
        refreshHosts()
    }

    /**
     * Manual reconnect for the Disconnected state: rebuilds the active transport
     * so the shared [fleet] stream re-opens a fresh socket (a no-op if there is
     * no active host).
     */
    fun reconnect() {
        active?.let { _transport.value = newTransport(it) }
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

    /**
     * Available backends for the spawn-sheet picker. Prefers `GET /backends`
     * (warden ≥ v8.16.7); a 404 (older daemon) or network error surfaces as a
     * failure so the caller can fall back to the static [com.warden.android.data.model.Backend] list.
     */
    suspend fun listBackends(): Result<List<BackendInfo>> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.listBackends()
            if (resp.isSuccessful) {
                Result.success(resp.body()?.backends ?: emptyList())
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** One-shot pipeline DAG snapshot for the pipelines list (init + pull-to-refresh). */
    suspend fun listPipelines(): Result<List<Pipeline>> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.listPipelines()
            if (resp.isSuccessful) {
                Result.success(resp.body()?.pipelines ?: emptyList())
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** One pipeline (with its jobs) for the detail screen. */
    suspend fun getPipeline(id: String): Result<Pipeline> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.getPipeline(id)
            if (resp.isSuccessful) {
                Result.success(resp.body() ?: Pipeline(id = id))
            } else {
                Result.failure(HttpStatusException(resp.code()))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Runs a pipeline lifecycle action (start/pause/resume/cancel/delete). On a
     * non-2xx the daemon's `{error}` message is surfaced verbatim — it carries the
     * useful 409 guidance (e.g. "pipeline has live jobs — cancel it first").
     */
    private suspend fun pipelineAction(call: suspend (WardenTransport) -> Response<*>): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = call(c)
            if (resp.isSuccessful) Result.success(Unit)
            else Result.failure(Exception(errorMessage(resp)))
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /** Start a pending pipeline. */
    suspend fun startPipeline(id: String): Result<Unit> = pipelineAction { it.api.startPipeline(id) }

    /** Pause a running pipeline. */
    suspend fun pausePipeline(id: String): Result<Unit> = pipelineAction { it.api.pausePipeline(id) }

    /** Resume a paused pipeline. */
    suspend fun resumePipeline(id: String): Result<Unit> = pipelineAction { it.api.resumePipeline(id) }

    /** Cancel (terminate) a live pipeline. */
    suspend fun cancelPipeline(id: String): Result<Unit> = pipelineAction { it.api.cancelPipeline(id) }

    /** Delete a pipeline record (409 while any job is live — cancel first). */
    suspend fun deletePipeline(id: String): Result<Unit> = pipelineAction { it.api.deletePipeline(id) }

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

    /**
     * Opens a terminal on the active host: a `POST /spawn` with `kind=terminal`,
     * where only [cwd] + optional [name] matter (backend/model/role/prompt are
     * ignored server-side). Reuses [spawn], so the same spawn-gate confirmation
     * flow applies if the daemon warns.
     */
    suspend fun createTerminal(cwd: String, name: String): SpawnOutcome =
        spawn(SpawnRequest(kind = Kind.TERMINAL, cwd = cwd.trim(), name = name.trim()))

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
     * Restores an orphaned agent: the daemon re-creates its tmux session and
     * resumes it, keeping the same id, worktree, and history. The row flips out
     * of the `orphaned` state on the next SSE snapshot.
     */
    suspend fun restoreAgent(id: String): Result<Unit> {
        val c = client ?: return Result.failure(IllegalStateException("no active connection"))
        return try {
            val resp = c.api.restore(id)
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
