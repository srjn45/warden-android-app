package com.warden.android.data

import com.warden.android.data.model.SessionList
import com.warden.android.data.terminal.TerminalListener
import com.warden.android.data.terminal.TerminalTransport
import kotlinx.coroutines.flow.Flow

/**
 * The transport surface [WardenRepository] drives for one active host: the
 * read-only REST calls ([api]), the live fleet [sessionStream], and a terminal
 * [openTerminal] attach. The real implementation is [WardenClient] (Retrofit +
 * okhttp-sse + WS); [com.warden.android.data.demo.DemoTransport] serves canned
 * fixtures so the app can be explored — and reviewed on the Play Store — without
 * a running daemon. Abstracting here means every screen above the repository is
 * identical in both modes.
 */
interface WardenTransport {
    /** Read + mutation REST surface (see [WardenApi]). */
    val api: WardenApi

    /** Live fleet snapshots (SSE in real mode; a fixture flow in demo mode). */
    fun sessionStream(): Flow<SessionList>

    /** Opens a terminal attach for [sessionId]. */
    fun openTerminal(sessionId: String, listener: TerminalListener): TerminalTransport
}
