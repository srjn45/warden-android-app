package com.warden.android.terminal

import android.os.Handler
import android.os.Looper
import com.warden.android.data.WardenRepository
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalState
import com.warden.android.data.terminal.TerminalTransport

/**
 * Wires a [RemoteTerminalSession] (the emulator) to a WS [TerminalTransport] and
 * marshals the two threads that meet here: WS callbacks arrive on OkHttp's reader
 * thread, but the emulator must only be touched on the UI thread, so every
 * inbound frame is posted to the main looper before it reaches the session.
 *
 * Lifecycle: the view reports its grid size once measured; the first report opens
 * the socket and creates the session, later reports resize it. Per design.md
 * §2.2 the initial resize is (re)sent on every [TerminalState.Attached] so the
 * server's `window-size latest` tmux pane matches this client.
 */
class TerminalController(
    private val repo: WardenRepository,
    private val sessionId: String,
) : TerminalListener {

    private val main = Handler(Looper.getMainLooper())
    private var transport: TerminalTransport? = null

    var session: RemoteTerminalSession? = null
        private set

    /** Fired (on main) when the session is first created, so the view can bind it. */
    var onSessionReady: ((RemoteTerminalSession) -> Unit)? = null

    /** Fired (on main) on each connection-state change. */
    var onStateChanged: ((TerminalState) -> Unit)? = null

    /** Fired (on main) when the screen changed and should repaint. */
    var onRepaint: (() -> Unit)? = null

    private var cols = 0
    private var rows = 0

    /** Called by the view when its measured grid size changes. */
    fun onGridSize(cols: Int, rows: Int) {
        this.cols = cols
        this.rows = rows
        val existing = session
        if (existing == null) {
            val created = RemoteTerminalSession(
                columns = cols,
                rows = rows,
                output = { bytes -> transport?.sendInput(bytes) },
                onScreenUpdated = { onRepaint?.invoke() },
            )
            session = created
            onSessionReady?.invoke(created)
            transport = repo.openTerminal(sessionId, this)
        } else {
            existing.resize(cols, rows)
            transport?.sendResize(cols, rows)
        }
    }

    /** Send user keystrokes to the PTY. */
    fun input(bytes: ByteArray) {
        transport?.sendInput(bytes)
    }

    /** Re-attach after a detach; hits the same live pane server-side. */
    fun reconnect() {
        transport?.detach()
        onStateChanged?.invoke(TerminalState.Connecting)
        transport = repo.openTerminal(sessionId, this)
    }

    /** Detach; the tmux session keeps running server-side. */
    fun dispose() {
        transport?.detach()
        transport = null
    }

    // --- TerminalListener (OkHttp reader thread) → hop to main ---

    override fun onOutput(data: ByteArray) {
        main.post { session?.append(data, data.size) }
    }

    override fun onState(state: TerminalState) {
        main.post {
            if (state is TerminalState.Attached && cols > 0 && rows > 0) {
                transport?.sendResize(cols, rows)
            }
            onStateChanged?.invoke(state)
        }
    }
}
