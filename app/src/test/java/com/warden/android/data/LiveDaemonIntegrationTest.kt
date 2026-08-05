package com.warden.android.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * End-to-end check against a REAL running warden daemon, exercising the exact
 * [WardenClient] transport the app uses — Retrofit/OkHttp REST plus the
 * okhttp-sse stream — not a mock. This is the strongest automated evidence that
 * the live agent list pipeline works, short of rendering on a device.
 *
 * Gated on env vars so it never runs (or leaks a token) in normal CI:
 *   WARDEN_TEST_BASE_URL  e.g. http://127.0.0.1:8765
 *   WARDEN_TEST_TOKEN     the daemon's WARDEN_TOKEN
 * Absent either → the test is skipped via JUnit Assume.
 *
 * Strictly read-only: it only calls /healthz, GET /sessions, and the SSE
 * stream. It never spawns, terminates, or deletes.
 */
class LiveDaemonIntegrationTest {

    private val baseUrlRaw: String? = System.getenv("WARDEN_TEST_BASE_URL")
    private val token: String? = System.getenv("WARDEN_TEST_TOKEN")

    private fun client(): WardenClient {
        val baseUrl = Connection.normalizeBaseUrl(baseUrlRaw!!)!!
        return WardenClient(Connection(label = baseUrl, baseUrl = baseUrl, token = token!!))
    }

    @Test
    fun restHealthAndSessions() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)
        val api = client().api

        val health = api.health()
        assertTrue("healthz should be 200, got ${health.code()}", health.isSuccessful)

        val sessions = api.listSessions()
        assertTrue("sessions should be 200, got ${sessions.code()}", sessions.isSuccessful)
        val body = sessions.body()
        assertNotNull("sessions body should decode", body)
        // Every session must at least carry the required id.
        body!!.sessions.forEach { assertTrue(it.id.isNotBlank()) }
        println("[live] REST ok — ${body.sessions.size} agent(s): " +
            body.sessions.joinToString { "${it.displayName}(${it.status})" })
    }

    @Test
    fun sseFirstSnapshot() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)

        val snapshot = withTimeoutOrNull(10_000) {
            client().sessionStream().first()
        }
        assertNotNull("expected an SSE snapshot within 10s", snapshot)
        println("[live] SSE ok — first snapshot carried ${snapshot!!.sessions.size} agent(s)")
    }
}
