package com.warden.android.data.terminal

/**
 * Connection state of a terminal attach socket.
 *
 * The daemon's agent attach (`signalSessionEnd=false`) uses `CloseNow()` on the
 * normal detach path, so an ordinary detach looks like an abnormal close, not a
 * clean 1000 (design.md §2.2). We therefore distinguish by whether the socket
 * ever opened: a drop *after* [Attached] is a benign [Detached] (session still
 * alive server-side → offer reconnect), while a failure *before* opening is a
 * real [Failed] (bad host / token / missing session).
 */
sealed interface TerminalState {
    data object Connecting : TerminalState
    data object Attached : TerminalState
    data object Detached : TerminalState
    data class Failed(val message: String) : TerminalState
}

/** Callbacks from a [TerminalTransport]. May fire on a background socket thread. */
interface TerminalListener {
    /** Raw xterm-256color PTY bytes from the server. */
    fun onOutput(data: ByteArray)

    /** A connection-state transition. */
    fun onState(state: TerminalState)
}

/** A live attach socket: write keystrokes/resize up, receive output via the listener. */
interface TerminalTransport {
    /** Keystrokes → PTY (binary). Large input is chunked under the server frame limit. */
    fun sendInput(data: ByteArray)

    /** Terminal size → tmux (text resize frame). No-op for non-positive dims. */
    fun sendResize(cols: Int, rows: Int)

    /** Detach this client; the server-side tmux session keeps running. */
    fun detach()
}

/**
 * Pure framing helpers, split out so they're unit-testable without a socket.
 */
internal object TerminalFraming {
    /**
     * The server rejects client binary frames larger than 1 MiB (closing the
     * connection). We chunk well under that so a big paste can't trip the limit.
     */
    const val MAX_INPUT_FRAME: Int = 512 * 1024

    /** Resize text frame, or null when either dimension is non-positive (never send). */
    fun resizeJson(cols: Int, rows: Int): String? =
        if (cols > 0 && rows > 0) "{\"cols\":$cols,\"rows\":$rows}" else null

    /** Splits [data] into frames of at most [max] bytes, preserving order. */
    fun chunkInput(data: ByteArray, max: Int = MAX_INPUT_FRAME): List<ByteArray> {
        if (data.size <= max) return listOf(data)
        val chunks = ArrayList<ByteArray>((data.size + max - 1) / max)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + max, data.size)
            chunks.add(data.copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }
}
