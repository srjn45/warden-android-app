package com.warden.android.data

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.warden.android.data.model.SessionList
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Transport for one warden [Connection]. Wraps a Retrofit surface for the
 * read-only REST calls and an okhttp-sse stream for the live fleet list.
 *
 * REST auth is a `Authorization: Bearer <token>` header ([BearerInterceptor]);
 * SSE/WS auth must be a `?token=` query param because a browser-style
 * `EventSource`/WS upgrade cannot set headers (design.md §3).
 */
class WardenClient(private val connection: Connection) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(BearerInterceptor(connection.token))
        // The SSE stream is long-lived and only speaks every ~25s (heartbeat),
        // so the read timeout must not kill an idle-but-healthy stream.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(connection.baseUrl)
        .client(httpClient)
        .addConverterFactory(WardenJson.asConverterFactory("application/json".toMediaType()))
        .build()

    val api: WardenApi = retrofit.create(WardenApi::class.java)

    /**
     * Subscribes to `GET /api/v1/events/stream`. Each SSE `data:` frame is a
     * full `{sessions:[…]}` snapshot; identical consecutive payloads are
     * deduped so the UI only recomposes on real changes. `:ping` heartbeats
     * arrive as comment lines and are never delivered as events by okhttp-sse.
     *
     * The flow completes on a clean stream end and propagates failures so the
     * caller can show a disconnected state and retry.
     */
    fun sessionStream(): Flow<SessionList> = callbackFlow {
        val url = connection.baseUrl.trimEnd('/') +
            "/api/v1/events/stream?token=" + connection.token

        val request = Request.Builder().url(url).build()
        var lastRaw: String? = null

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                if (data.isBlank() || data == lastRaw) return
                lastRaw = data
                val snapshot = runCatching { WardenJson.decodeFromString<SessionList>(data) }
                    .getOrNull() ?: return
                trySend(snapshot)
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                close(t ?: StreamClosedException(response?.code))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(httpClient).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    /** Thrown when the SSE stream fails; [code] is the HTTP status if any. */
    class StreamClosedException(val code: Int?) :
        Exception("SSE stream closed" + (code?.let { " (HTTP $it)" } ?: ""))
}

/** Adds the bearer token to every REST request. */
class BearerInterceptor(private val token: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        return chain.proceed(req)
    }
}
