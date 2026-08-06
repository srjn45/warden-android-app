package com.warden.android.data.model

import com.warden.android.data.WardenJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pipeline wire shapes (`GET /api/v1/pipelines` and
 * `/pipelines/{id}`) decode through [WardenJson] — the exact Json the Retrofit
 * converter uses. Fields the app renders (status, jobs, depends_on, session_id)
 * must survive, and a sparse job must still decode with safe defaults.
 */
class PipelineModelsTest {

    private val listBody = """
        {"pipelines":[
          {"id":"backend-registry","name":"backend-registry","repo":"/home/x/dev/warden",
           "status":"running","tags":["owner:me"],
           "jobs":[
             {"id":"store","status":"done","type":"development","session_id":"br-store"},
             {"id":"api","status":"running","type":"development","depends_on":["store"],
              "session_id":"br-api","worktree":"from:store"},
             {"id":"docs","status":"pending","depends_on":["api"]}
           ]}
        ]}
    """.trimIndent()

    @Test
    fun `the pipelines list body decodes with jobs and their dependencies`() {
        val resp = WardenJson.decodeFromString<PipelineList>(listBody)
        assertEquals(1, resp.pipelines.size)
        val p = resp.pipelines[0]
        assertEquals("backend-registry", p.id)
        assertEquals(PipelineStatus.RUNNING, p.status)
        assertEquals(3, p.jobs.size)
        // depends_on maps to dependsOn; absent on the first job → empty.
        assertTrue(p.jobs[0].dependsOn.isEmpty())
        assertEquals(listOf("store"), p.jobs[1].dependsOn)
        assertEquals("br-api", p.jobs[1].sessionId)
        assertEquals("from:store", p.jobs[1].worktree)
    }

    @Test
    fun `doneCount counts only settled jobs`() {
        val p = WardenJson.decodeFromString<PipelineList>(listBody).pipelines[0]
        // store=done (settled), api=running, docs=pending → 1 settled.
        assertEquals(1, p.doneCount)
        assertEquals("backend-registry", p.displayName)
    }

    @Test
    fun `a pipeline detail body decodes the same shape as a list element`() {
        val body = """
            {"id":"solo","name":"","repo":"","status":"done",
             "jobs":[{"id":"only","status":"done"}]}
        """.trimIndent()
        val p = WardenJson.decodeFromString<Pipeline>(body)
        assertEquals("solo", p.id)
        // Blank name falls back to id for the row title.
        assertEquals("solo", p.displayName)
        assertEquals(1, p.doneCount)
    }

    @Test
    fun `a sparse job decodes with safe defaults and no session`() {
        val job = WardenJson.decodeFromString<PipelineJob>("""{"id":"lonely"}""")
        assertEquals("lonely", job.id)
        assertEquals("", job.status)
        assertEquals("", job.sessionId)
        assertTrue(job.dependsOn.isEmpty())
        assertEquals(false, job.supervised)
    }
}
