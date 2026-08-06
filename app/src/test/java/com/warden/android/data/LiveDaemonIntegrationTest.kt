package com.warden.android.data

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
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
import java.util.concurrent.atomic.AtomicBoolean
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

    /** The spawn-sheet role picker source (`GET /roles`) — read-only. */
    @Test
    fun rolesList() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)
        val resp = client().api.listRoles()
        assertTrue("roles should be 200, got ${resp.code()}", resp.isSuccessful)
        val roles = resp.body()?.roles ?: emptyList()
        // The daemon documents "general first, then alphabetical"; at minimum it
        // returns the built-ins, each with a name.
        roles.forEach { assertTrue("role name should be non-blank", it.name.isNotBlank()) }
        println("[live] roles ok — ${roles.size} role(s): ${roles.joinToString { it.name }}")
    }

    /** The spawn-sheet working-dir browser source (`GET /fs/dirs`) — read-only. */
    @Test
    fun dirsList() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)
        // Empty path = the user's home directory.
        val resp = client().api.listDirs(null)
        assertTrue("fs/dirs should be 200, got ${resp.code()}", resp.isSuccessful)
        val listing = resp.body()
        assertNotNull("dir listing should decode", listing)
        listing!!.entries.forEach { assertTrue(it.path.isNotBlank()) }
        println("[live] fs/dirs ok — home has ${listing.entries.size} subdir(s)")
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

    /**
     * Verifies the daemon-side contract that unblocks touch swipe-scroll: since
     * warden v8.16.4, a per-agent attach runs `tmux set-option mouse on`, so the
     * server's attach handshake emits the mouse-tracking DECSET (`ESC [ ? 1000/1002/1003 h`,
     * SGR `1006`). We feed the real attach stream into an actual Termux
     * [TerminalEmulator] — the same VT state machine the app renders — and assert
     * [TerminalEmulator.isMouseTrackingActive] flips true. That boolean is the exact
     * gate `RemoteTerminalView.onScroll` opens before forwarding swipes as
     * MOUSE_WHEELUP/DOWN, so this is the strongest device-free evidence that
     * swipe-scroll is now live against this daemon.
     *
     * Still strictly read-only: emulator output ([TerminalOutput.write]) is dropped,
     * never sent back; no input, no resize. (Attaching does flip the *server's* own
     * `mouse on` option for the session — that's the shipped behavior we're checking,
     * not a disruption to the agent's work.)
     */
    @Test
    fun wsAttachEnablesMouseTracking() = runBlocking {
        assumeTrue("set WARDEN_TEST_BASE_URL + WARDEN_TEST_TOKEN to run", baseUrlRaw != null && token != null)
        val c = client()
        val sessions = c.api.listSessions().body()?.sessions ?: emptyList()
        assumeTrue("need at least one live session to attach", sessions.isNotEmpty())
        val target = sessions.first()

        // A bare emulator with no-op sinks — crucially NOT RemoteTerminalSession,
        // whose log hooks call android.util.Log (unavailable in a plain JVM test).
        val sink = object : TerminalOutput(), TerminalSessionClient {
            override fun write(data: ByteArray?, offset: Int, count: Int) {} // drop: read-only
            override fun titleChanged(oldTitle: String?, newTitle: String?) {}
            override fun onCopyTextToClipboard(text: String?) {}
            override fun onPasteTextFromClipboard() {}
            override fun onBell() {}
            override fun onColorsChanged() {}
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {}
            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
            override fun onPasteTextFromClipboard(session: TerminalSession) {}
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) {}
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
        val emulator = TerminalEmulator(sink, 80, 24, 2000, sink)

        val mouseActive = AtomicBoolean(false)
        val settled = CountDownLatch(1)
        val rawHex = StringBuilder()

        val transport = c.openTerminal(target.id, object : TerminalListener {
            // okhttp delivers frames on a single reader thread, so the emulator
            // (not thread-safe) is only ever touched here — no cross-thread hazard.
            override fun onOutput(data: ByteArray) {
                if (rawHex.length < 8192) {
                    data.forEach { rawHex.append("%02x".format(it.toInt() and 0xff)) }
                }
                runCatching { emulator.append(data, data.size) } // guard stray OSC52→Base64
                if (emulator.isMouseTrackingActive && mouseActive.compareAndSet(false, true)) {
                    settled.countDown()
                }
            }

            override fun onState(state: TerminalState) {
                if (state is TerminalState.Failed) settled.countDown()
            }
        })

        val flipped = settled.await(10, TimeUnit.SECONDS)
        transport.detach()

        // Which mouse DECSETs actually arrived — evidence in the test log either way.
        val raw = rawHex.toString()
        val seen = listOf("1000", "1002", "1003", "1006")
            .filter { raw.contains("1b5b3f${it.map { d -> "3${d}" }.joinToString("")}68") }
        println(
            "[live] mouse-tracking probe on ${target.displayName}: " +
                "isMouseTrackingActive=${mouseActive.get()} (settled=$flipped), " +
                "DECSET seen=$seen",
        )
        assertTrue(
            "expected emulator.isMouseTrackingActive within 10s of attach — " +
                "daemon should send mouse DECSET (warden ≥ v8.16.4). Raw prefix: " +
                raw.take(160),
            mouseActive.get(),
        )
    }
}
