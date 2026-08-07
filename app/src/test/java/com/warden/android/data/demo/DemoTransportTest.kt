package com.warden.android.data.demo

import com.warden.android.data.model.DeleteRequest
import com.warden.android.data.model.PipelineStatus
import com.warden.android.data.model.SpawnRequest
import com.warden.android.data.model.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the offline demo transport behaves like a daemon: reads return the
 * fixtures, and every mutation updates the in-memory state so the fleet reacts
 * (delete removes, terminate orphans, restore resumes, spawn adds) and the
 * live [DemoTransport.sessionStream] reflects it. This is the automated stand-in
 * for a device run of demo mode.
 */
class DemoTransportTest {

    @Test
    fun `reads return fixtures`() = runBlocking {
        val t = DemoTransport()
        assertEquals("ok", t.api.health().body()?.status)
        assertTrue((t.api.listSessions().body()?.sessions?.size ?: 0) >= 8)
        assertTrue((t.api.listRoles().body()?.roles?.size ?: 0) >= 3)
        assertTrue((t.api.listBackends().body()?.backends?.isNotEmpty()) == true)
        assertTrue((t.api.listPipelines().body()?.pipelines?.size ?: 0) >= 2)
        assertNotNull(t.api.getPipeline("demo-p1").body())
    }

    @Test
    fun `stream mirrors the current fleet`() = runBlocking {
        val t = DemoTransport()
        val fromStream = t.sessionStream().first().sessions
        val fromList = t.api.listSessions().body()?.sessions
        assertEquals(fromList, fromStream)
    }

    @Test
    fun `terminate orphans and restore resumes`() = runBlocking {
        val t = DemoTransport()
        val id = "demo-a1"
        t.api.terminate(id)
        assertEquals(Status.ORPHANED, statusOf(t, id))
        t.api.restore(id)
        assertEquals(Status.WORKING, statusOf(t, id))
    }

    @Test
    fun `delete removes the row from the live stream`() = runBlocking {
        val t = DemoTransport()
        val id = "demo-a3"
        t.api.delete(id, DeleteRequest())
        assertNull(t.sessionStream().first().sessions.firstOrNull { it.id == id })
    }

    @Test
    fun `spawn adds an agent`() = runBlocking {
        val t = DemoTransport()
        val before = t.api.listSessions().body()!!.sessions.size
        val created = t.api.spawn(SpawnRequest(name = "scratch", prompt = "hello")).body()
        assertNotNull(created)
        assertEquals("scratch", created!!.name)
        assertEquals(before + 1, t.api.listSessions().body()!!.sessions.size)
        assertNotNull(t.sessionStream().first().sessions.firstOrNull { it.id == created.id })
    }

    @Test
    fun `pipeline actions change status and delete removes`() = runBlocking {
        val t = DemoTransport()
        t.api.pausePipeline("demo-p1")
        assertEquals(PipelineStatus.PAUSED, t.api.getPipeline("demo-p1").body()?.status)
        t.api.resumePipeline("demo-p1")
        assertEquals(PipelineStatus.RUNNING, t.api.getPipeline("demo-p1").body()?.status)
        t.api.deletePipeline("demo-p1")
        assertFalse(t.api.listPipelines().body()!!.pipelines.any { it.id == "demo-p1" })
    }

    private suspend fun statusOf(t: DemoTransport, id: String): String? =
        t.api.listSessions().body()?.sessions?.firstOrNull { it.id == id }?.status
}
