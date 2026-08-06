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
}
