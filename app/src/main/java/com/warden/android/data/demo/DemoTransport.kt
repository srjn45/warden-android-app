package com.warden.android.data.demo

import com.warden.android.data.WardenApi
import com.warden.android.data.WardenTransport
import com.warden.android.data.model.BackendsResponse
import com.warden.android.data.model.DeleteRequest
import com.warden.android.data.model.DeleteResponse
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.HealthResponse
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.PipelineList
import com.warden.android.data.model.PipelineStatus
import com.warden.android.data.model.RemoveWorktreeRequest
import com.warden.android.data.model.RolesResponse
import com.warden.android.data.model.Session
import com.warden.android.data.model.SessionList
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.Status
import com.warden.android.data.model.StatusResponse
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

/**
 * Offline [WardenTransport] backed by [DemoData]. Reads return fixtures and
 * mutations update in-memory [MutableStateFlow] state, so the fleet reacts to
 * actions exactly like a real daemon: deleting a row removes it, terminating an
 * agent orphans it, restoring an orphan makes it work again, spawning adds one.
 * Because the fleet is a StateFlow, [sessionStream] pushes each change straight
 * to the list — the same live feel as SSE, with no network.
 *
 * A fresh instance (and therefore a pristine fixture set) is built every time the
 * demo host is activated.
 */
class DemoTransport : WardenTransport {

    private val sessions = MutableStateFlow(DemoData.sessions())
    private val pipelines = MutableStateFlow(DemoData.pipelines())
    private val spawnCounter = AtomicInteger(0)

    override val api: WardenApi = object : WardenApi {

        override suspend fun health(): Response<HealthResponse> =
            Response.success(HealthResponse(status = "ok"))

        override suspend fun listSessions(): Response<SessionList> =
            Response.success(SessionList(sessions.value))

        override suspend fun listRoles(): Response<RolesResponse> =
            Response.success(RolesResponse(DemoData.roles()))

        override suspend fun listBackends(): Response<BackendsResponse> =
            Response.success(BackendsResponse(DemoData.backends()))

        override suspend fun listDirs(path: String?): Response<DirListing> =
            Response.success(DemoData.dirListing(path))

        override suspend fun listPipelines(): Response<PipelineList> =
            Response.success(PipelineList(pipelines.value))

        override suspend fun getPipeline(id: String): Response<Pipeline> =
            pipelines.value.firstOrNull { it.id == id }
                ?.let { Response.success(it) }
                ?: Response.error(404, emptyBody())

        override suspend fun startPipeline(id: String): Response<StatusResponse> =
            setPipelineStatus(id, PipelineStatus.RUNNING)

        override suspend fun pausePipeline(id: String): Response<StatusResponse> =
            setPipelineStatus(id, PipelineStatus.PAUSED)

        override suspend fun resumePipeline(id: String): Response<StatusResponse> =
            setPipelineStatus(id, PipelineStatus.RUNNING)

        override suspend fun cancelPipeline(id: String): Response<StatusResponse> =
            setPipelineStatus(id, PipelineStatus.CANCELED)

        override suspend fun deletePipeline(id: String): Response<StatusResponse> {
            pipelines.value = pipelines.value.filterNot { it.id == id }
            return ok()
        }

        override suspend fun spawn(req: SpawnRequest): Response<Session> {
            val n = spawnCounter.incrementAndGet()
            val session = Session(
                id = "demo-new-$n",
                name = req.name.ifBlank { "new-agent-$n" },
                status = Status.SPAWNING,
                repo = req.repo,
                role = req.role,
                backend = req.backend.ifBlank { "claude" },
                model = req.model,
                prompt = req.prompt,
                workdir = req.cwd,
                subject = req.prompt.take(60),
                lastPaneExcerpt = "spawning…",
                createdAt = "2026-08-06T14:35:00Z",
                updatedAt = "2026-08-06T14:35:00Z",
            )
            sessions.value = sessions.value + session
            return Response.success(session)
        }

        override suspend fun terminate(id: String): Response<StatusResponse> =
            setSessionStatus(id, Status.ORPHANED)

        override suspend fun restore(id: String): Response<StatusResponse> =
            setSessionStatus(id, Status.WORKING)

        override suspend fun delete(id: String, req: DeleteRequest): Response<DeleteResponse> {
            sessions.value = sessions.value.filterNot { it.id == id }
            return Response.success(DeleteResponse(status = "ok"))
        }

        override suspend fun removeWorktree(
            id: String,
            req: RemoveWorktreeRequest,
        ): Response<StatusResponse> = ok()
    }

    override fun sessionStream(): Flow<SessionList> = sessions.map { SessionList(it) }

    override fun openTerminal(sessionId: String, listener: TerminalListener): TerminalTransport {
        val name = sessions.value.firstOrNull { it.id == sessionId }?.displayName ?: sessionId
        return DemoTerminalTransport(name, listener)
    }

    private fun setSessionStatus(id: String, status: String): Response<StatusResponse> {
        sessions.value = sessions.value.map {
            if (it.id == id) it.copy(status = status) else it
        }
        return ok()
    }

    private fun setPipelineStatus(id: String, status: String): Response<StatusResponse> {
        pipelines.value = pipelines.value.map {
            if (it.id == id) it.copy(status = status) else it
        }
        return ok()
    }

    private fun ok(): Response<StatusResponse> = Response.success(StatusResponse(status = "ok"))

    private fun emptyBody() =
        okhttp3.ResponseBody.create(null, "")
}
