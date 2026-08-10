package com.warden.android.data.model

import com.warden.android.data.WardenJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `kind` discriminator: absent or empty must read as an AGENT (back-compat
 * with records that predate the field and every AI backend), and only the
 * explicit `"terminal"` marks a terminal. Also verifies the field decodes off the
 * wire so the SSE snapshot classifies live sessions correctly.
 */
class SessionKindTest {

    @Test
    fun `absent kind reads as agent`() {
        val s = Session(id = "a1")
        assertEquals("", s.kind)
        assertFalse(s.isTerminal)
    }

    @Test
    fun `explicit terminal kind is a terminal`() {
        val s = Session(id = "t1", kind = Kind.TERMINAL)
        assertTrue(s.isTerminal)
    }

    @Test
    fun `terminal wire shape decodes - null name coerces to blank, agent- id prefix is not agent-ness`() {
        // The live daemon returns terminals with an explicit "name":null and an
        // id that is still prefixed "agent-"; kind alone decides terminal-ness.
        val json = """
            [
              {"id":"agent-b858829e","status":"working","kind":"terminal","name":null},
              {"id":"agent-f1943d9b","status":"working","kind":"terminal"}
            ]
        """.trimIndent()
        val list = WardenJson.decodeFromString(ListSerializer(Session.serializer()), json)
        assertEquals(2, list.size)
        assertTrue(list.all { it.isTerminal })
        // null name coerced to the "" default → displayName falls back to id.
        assertEquals("", list.first { it.id == "agent-b858829e" }.name)
        assertEquals("agent-b858829e", list.first { it.id == "agent-b858829e" }.displayName)
    }

    @Test
    fun `kind decodes from a sessions payload, missing field defaults to agent`() {
        val json = """
            [
              {"id":"a1","status":"working"},
              {"id":"t1","status":"working","kind":"terminal"},
              {"id":"a2","status":"idle","kind":"agent"}
            ]
        """.trimIndent()
        val list = WardenJson.decodeFromString(ListSerializer(Session.serializer()), json)
        assertFalse(list.first { it.id == "a1" }.isTerminal)
        assertTrue(list.first { it.id == "t1" }.isTerminal)
        assertFalse(list.first { it.id == "a2" }.isTerminal)
    }
}
