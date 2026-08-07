package com.warden.android.data.demo

import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalState
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A fake [TerminalTransport] for demo mode. It transitions Connecting → Attached
 * and streams a scripted [DemoData.terminalTranscript]; keystrokes are echoed
 * locally so typing feels live. No socket, no server — [detach] just ends it.
 */
class DemoTerminalTransport(
    sessionName: String,
    private val listener: TerminalListener,
) : TerminalTransport {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        scope.launch {
            listener.onState(TerminalState.Connecting)
            delay(250)
            listener.onState(TerminalState.Attached)
            delay(150)
            listener.onOutput(DemoData.terminalTranscript(sessionName).toByteArray())
        }
    }

    /** Local echo so the demo terminal responds to typing without a PTY. */
    override fun sendInput(data: ByteArray) {
        listener.onOutput(data)
    }

    override fun sendResize(cols: Int, rows: Int) = Unit

    override fun detach() {
        listener.onState(TerminalState.Detached)
        scope.cancel()
    }
}
