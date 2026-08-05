package com.warden.android.data

import com.warden.android.data.model.SessionList
import com.warden.android.data.model.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the data layer decodes a real-shaped daemon snapshot. The fixture
 * mirrors the actual `GET /api/v1/sessions` payload observed against a live
 * daemon (2026-08-06): it carries fields the app does not model — `events`,
 * `context_checked_at`, `tags` — plus an empty `type` for a free-form agent.
 * Decoding must tolerate all of that (WardenJson.ignoreUnknownKeys).
 */
class SessionParsingTest {

    private val fixture = """
        {
          "sessions": [
            {
              "id": "agent-1b9551b2",
              "name": "wd-app",
              "type": "",
              "ticket": "",
              "tmux_session": "agent-1b9551b2",
              "claude_session_id": "22d1c113-fc77-4ca1-ac2a-ee6ff9fcd9d5",
              "workdir": "/home/srjn45/dev/warden-android-app",
              "subject": "Implementing Android app.",
              "status": "working",
              "pid": 0,
              "created_at": "2026-08-05T23:57:00Z",
              "updated_at": "2026-08-06T00:10:00Z",
              "context_state": "ok",
              "context_tokens": 42000,
              "context_checked_at": "2026-08-06T00:10:00Z",
              "tags": ["android", "mvp"],
              "events": [
                { "ts": "2026-08-06T00:10:00Z", "type": "status", "detail": "working" }
              ]
            },
            {
              "id": "agent-4a454152",
              "name": "warden-app-dev",
              "type": "analysis",
              "status": "waiting_for_input",
              "subject": "Deploying native Android client for Warden daemon.",
              "context_state": "warning",
              "created_at": "2026-08-05T20:00:00Z",
              "updated_at": "2026-08-06T00:05:00Z",
              "exit_code": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesSnapshotWithUnknownFields() {
        val list = WardenJson.decodeFromString<SessionList>(fixture)
        assertEquals(2, list.sessions.size)

        val first = list.sessions[0]
        assertEquals("agent-1b9551b2", first.id)
        assertEquals("wd-app", first.displayName)
        assertEquals("", first.type)
        assertEquals(Status.WORKING, first.status)
        assertEquals("ok", first.contextState)
        assertEquals(42000, first.contextTokens)
        assertEquals(listOf("android", "mvp"), first.tags)

        val second = list.sessions[1]
        assertEquals(Status.WAITING_FOR_INPUT, second.status)
        assertNull(second.exitCode)
        // Absent optional fields fall back to defaults, not crashes.
        assertEquals("", second.model)
        assertEquals(0, second.pid)
    }

    @Test
    fun displayNameFallsBackToId() {
        val json = """{"sessions":[{"id":"agent-x","type":"","status":"idle",
            "created_at":"","updated_at":""}]}"""
        val list = WardenJson.decodeFromString<SessionList>(json)
        assertEquals("agent-x", list.sessions[0].displayName)
    }

    @Test
    fun normalizeBaseUrlAddsSchemeAndDefaultPort() {
        assertEquals("http://100.64.0.1:8765/", Connection.normalizeBaseUrl("100.64.0.1"))
        assertEquals("http://100.64.0.1:8765/", Connection.normalizeBaseUrl("100.64.0.1:8765"))
        assertEquals("http://100.64.0.1:8765/", Connection.normalizeBaseUrl("http://100.64.0.1:8765/"))
        assertEquals("http://host:9000/", Connection.normalizeBaseUrl("host:9000"))
    }

    @Test
    fun normalizeBaseUrlKeepsHttpsWithoutForcingPort() {
        assertEquals("https://box.tailnet.ts.net/", Connection.normalizeBaseUrl("https://box.tailnet.ts.net"))
        assertEquals("https://box.ts.net:8443/", Connection.normalizeBaseUrl("https://box.ts.net:8443"))
    }

    @Test
    fun normalizeBaseUrlRejectsEmpty() {
        assertNull(Connection.normalizeBaseUrl(""))
        assertNull(Connection.normalizeBaseUrl("   "))
    }

    @Test
    fun normalizeBaseUrlHandlesIpv6() {
        assertTrue(Connection.normalizeBaseUrl("[::1]:8765")!!.startsWith("http://[::1]:8765"))
    }
}
