package com.warden.android.terminal

import android.util.Log
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * Drives Termux's [TerminalEmulator] from a *remote* PTY (the daemon's WS attach
 * socket) instead of a local subprocess. Termux's own `TerminalSession` is final
 * and spawns a JNI subprocess, so it can't be reused for a remote stream — but
 * the emulator itself is a pure VT/xterm state machine with no process coupling.
 *
 * This class IS the emulator's [TerminalOutput]: bytes the emulator emits
 * (device-status/cursor reports, mouse events, etc.) are handed to [output],
 * which the caller wires to the WS transport. Server→client bytes arrive via
 * [append]. It also serves as the emulator's [TerminalSessionClient]; the
 * emulator only ever calls cursor-style and log hooks on it.
 *
 * **Threading:** the emulator is not thread-safe. Every method here must be
 * called on a single thread (the UI thread). The view marshals WS bytes onto
 * that thread before calling [append].
 */
class RemoteTerminalSession(
    columns: Int,
    rows: Int,
    transcriptRows: Int = DEFAULT_TRANSCRIPT_ROWS,
    private val output: (ByteArray) -> Unit,
    private val onScreenUpdated: () -> Unit,
) : TerminalOutput(), TerminalSessionClient {

    val emulator: TerminalEmulator = TerminalEmulator(
        this,
        columns.coerceAtLeast(MIN_DIM),
        rows.coerceAtLeast(MIN_DIM),
        transcriptRows,
        this,
    )

    /** Feed raw server→client PTY bytes into the emulator, then request a repaint. */
    fun append(data: ByteArray, length: Int) {
        emulator.append(data, length)
        onScreenUpdated()
    }

    /** Resize the emulator grid. The caller separately tells the server (resize frame). */
    fun resize(columns: Int, rows: Int) {
        emulator.resize(columns.coerceAtLeast(MIN_DIM), rows.coerceAtLeast(MIN_DIM))
        onScreenUpdated()
    }

    // --- TerminalOutput: bytes the emulator produces travel up to the server ---
    override fun write(data: ByteArray, offset: Int, count: Int) {
        if (count <= 0) return
        output(data.copyOfRange(offset, offset + count))
    }

    override fun titleChanged(oldTitle: String?, newTitle: String?) {}
    override fun onCopyTextToClipboard(text: String?) {}
    override fun onPasteTextFromClipboard() {}
    override fun onBell() {}
    override fun onColorsChanged() {}

    // --- TerminalSessionClient: the emulator only uses cursor-style + log* ---
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message, e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(TAG, "terminal", e) }

    private companion object {
        const val TAG = "RemoteTerminal"
        const val MIN_DIM = 4
        const val DEFAULT_TRANSCRIPT_ROWS = 2000
    }
}
