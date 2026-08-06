package com.warden.android.data.model

import com.warden.android.data.WardenJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the P2 spawn/confirmation wire shapes against [WardenJson] — the exact
 * Json the Retrofit converter uses. The key contract is that unset fields are
 * OMITTED (not sent as empty/false), so the daemon applies its own defaults, and
 * that the 428 spawn-gate body decodes into a usable [Verdict].
 */
class SpawnModelsTest {

    @Test
    fun `an all-default spawn request serializes to an empty object`() {
        // No fields set → nothing on the wire → daemon uses every default
        // (empty backend = claude, absent cwd = home, free-form type).
        assertEquals("{}", WardenJson.encodeToString(SpawnRequest()))
    }

    @Test
    fun `only the fields the user filled in are serialized`() {
        val json = WardenJson.encodeToString(
            SpawnRequest(backend = "aider", cwd = "/home/x/proj", prompt = "fix the build"),
        )
        assertTrue(json.contains("\"backend\":\"aider\""))
        assertTrue(json.contains("\"cwd\":\"/home/x/proj\""))
        assertTrue(json.contains("\"prompt\":\"fix the build\""))
        // Untouched fields must not appear — especially not force=false.
        assertFalse(json.contains("\"name\""))
        assertFalse(json.contains("\"model\""))
        assertFalse(json.contains("\"force\""))
        assertFalse(json.contains("\"role\""))
    }

    @Test
    fun `force is only serialized when set true (the 428 retry)`() {
        assertFalse(WardenJson.encodeToString(SpawnRequest()).contains("force"))
        assertTrue(WardenJson.encodeToString(SpawnRequest(force = true)).contains("\"force\":true"))
    }

    @Test
    fun `a 428 confirmation body decodes into a verdict`() {
        val body = """
            {"confirmation_required":true,
             "verdict":{"elevated":true,"level":2,"agent_count":9,"max_agents":10,
                        "reason":"memory pressure elevated"}}
        """.trimIndent()
        val conf = WardenJson.decodeFromString<ConfirmationResponse>(body)
        assertTrue(conf.confirmation_required)
        assertEquals("memory pressure elevated", conf.verdict.reason)
        assertEquals(9, conf.verdict.agent_count)
        assertEquals(10, conf.verdict.max_agents)
    }

    @Test
    fun `the delete response surfaces a live-agent warning`() {
        val body = """{"status":"deleted","warning":"the agent may still be live"}"""
        val resp = WardenJson.decodeFromString<DeleteResponse>(body)
        assertEquals("the agent may still be live", resp.warning)
    }

    @Test
    fun `the backend registry leads with claude and has unique ids`() {
        assertEquals("claude", Backend.ALL.first().id)
        assertEquals(Backend.DEFAULT, Backend.ALL.first())
        val ids = Backend.ALL.map { it.id }
        assertEquals("backend ids must be unique", ids.size, ids.toSet().size)
    }

    @Test
    fun `the plain-shell terminal backend is offered`() {
        val terminal = Backend.ALL.firstOrNull { it.id == "terminal" }
        assertEquals(Backend("terminal", "Terminal (plain shell)"), terminal)
    }

    @Test
    fun `labelFor maps ids, defaults blank to claude, and passes unknowns through`() {
        assertEquals("Terminal (plain shell)", Backend.labelFor("terminal"))
        assertEquals("Claude Code", Backend.labelFor(""))
        assertEquals("mystery", Backend.labelFor("mystery"))
    }

    @Test
    fun `the backends endpoint body decodes with all four fields`() {
        val body = """
            {"backends":[
              {"id":"claude","display_name":"Claude Code","default":true,"available":true},
              {"id":"terminal","display_name":"Terminal (plain shell)","default":false,"available":false}
            ]}
        """.trimIndent()
        val resp = WardenJson.decodeFromString<BackendsResponse>(body)
        assertEquals(2, resp.backends.size)
        val claude = resp.backends[0]
        assertEquals("claude", claude.id)
        assertTrue(claude.default)
        assertTrue(claude.available)
        val terminal = resp.backends[1]
        assertEquals("Terminal (plain shell)", terminal.label)
        assertFalse(terminal.available)
        assertEquals(Backend("terminal", "Terminal (plain shell)"), terminal.toBackend())
    }

    @Test
    fun `a backend missing the available field defaults to available (never grey out on a guess)`() {
        val info = WardenJson.decodeFromString<BackendInfo>("""{"id":"aider","display_name":"Aider"}""")
        assertTrue(info.available)
        // A blank display_name falls back to this build's static label, then the raw id.
        assertEquals("Aider", WardenJson.decodeFromString<BackendInfo>("""{"id":"aider"}""").label)
        assertEquals("mystery", WardenJson.decodeFromString<BackendInfo>("""{"id":"mystery"}""").label)
    }

    @Test
    fun `staticInfos mirrors ALL with the default flagged and all available`() {
        val infos = Backend.staticInfos()
        assertEquals(Backend.ALL.map { it.id }, infos.map { it.id })
        assertTrue("every static backend is assumed installed", infos.all { it.available })
        assertEquals(
            "exactly the claude entry carries the default flag",
            listOf("claude"),
            infos.filter { it.default }.map { it.id },
        )
    }
}
