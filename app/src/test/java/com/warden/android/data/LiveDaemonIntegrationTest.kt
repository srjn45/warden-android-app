package com.warden.android.data

import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    /**
     * Exercises the real WS attach transport ([WardenClient.openTerminal]) end to
     * end: it opens the binary WebSocket to a live session and asserts the
     * handshake + `?token=` auth reach [TerminalState.Attached].
     *
     * Strictly non-disruptive: it only reads server→client bytes and then
     * detaches. It sends NO input and NO resize, so an attached agent's tmux pane
     * (size, keystrokes) is never touched. An idle agent may emit zero bytes
     * (the stream is pure silence when quiet), so byte count is logged, not asserted.
     */
    @Test
    fun wsAttachReachesAttached() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)
        val c = client()
        val sessions = c.api.listSessions().body()?.sessions ?: emptyList()
        assumeTrue("need at least one live session to attach", sessions.isNotEmpty())
        val target = sessions.first()

        val done = CountDownLatch(1)
        val bytesRead = AtomicInteger(0)
        val failure = arrayOfNulls<String>(1)

        val transport = c.openTerminal(target.id, object : TerminalListener {
            override fun onOutput(data: ByteArray) {
                bytesRead.addAndGet(data.size)
            }

            override fun onState(state: TerminalState) {
                when (state) {
                    TerminalState.Attached -> done.countDown()
                    is TerminalState.Failed -> {
                        failure[0] = state.message
                        done.countDown()
                    }
                    else -> Unit
                }
            }
        })

        val reached = done.await(10, TimeUnit.SECONDS)
        transport.detach()

        assertNull("attach failed: ${failure[0]}", failure[0])
        assertTrue("expected WS attach within 10s", reached)
        println(
            "[live] WS attach ok — attached to ${target.displayName}; " +
                "read ${bytesRead.get()} byte(s), sent no input/resize",
        )
    }
}
