package com.warden.android.data.terminal

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp binary-WebSocket implementation of the daemon's terminal attach
 * (`GET /api/v1/sessions/{id}/attach`), per the contract in design.md §2.2:
 *
 * - No WebSocket subprotocol; auth is the `?token=` query param baked into [url].
 * - server → client frames are always binary raw PTY bytes → [TerminalListener.onOutput].
 * - client → server: binary = keystrokes (chunked under the 1 MiB server limit);
 *   text = resize JSON only.
 * - Keepalive is OkHttp's `pingInterval` (configured on the shared client); the
 *   bridge sends no app-level pings.
 * - Any close/failure *after* the socket opened is a benign detach (reconnect),
 *   never surfaced as an error; a failure *before* opening is a real error.
 */
class WsTerminalTransport(
    client: OkHttpClient,
    url: String,
    private val listener: TerminalListener,
) : TerminalTransport {

    @Volatile
    private var opened = false
    private val webSocket: WebSocket

    init {
        listener.onState(TerminalState.Connecting)
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                listener.onState(TerminalState.Attached)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                listener.onOutput(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // The server never sends text frames to the client — ignore.
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Acknowledge the close handshake; the session lives on server-side.
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onState(TerminalState.Detached)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onState(
                    if (opened) TerminalState.Detached
                    else TerminalState.Failed(failureMessage(t, response)),
                )
            }
        })
    }

    override fun sendInput(data: ByteArray) {
        if (data.isEmpty()) return
        for (chunk in TerminalFraming.chunkInput(data)) {
            webSocket.send(chunk.toByteString())
        }
    }

    override fun sendResize(cols: Int, rows: Int) {
        TerminalFraming.resizeJson(cols, rows)?.let { webSocket.send(it) }
    }

    override fun detach() {
        webSocket.close(NORMAL_CLOSURE, null)
    }

    private fun failureMessage(t: Throwable, response: Response?): String = when (response?.code) {
        401, 403 -> "Unauthorized — check the token"
        404 -> "Agent not found"
        null -> t.message ?: "Could not reach the daemon"
        else -> "Attach failed (HTTP ${response.code})"
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
    }
}
