package com.warden.android.data.model

import com.warden.android.data.WardenJson
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule contract (warden ≥ scheduled-agents): the daemon's easy-to-misread
 * `kind` (cadence: cron|at) vs `mode` (fire type: agent|pipeline), the durable
 * last-run pointer, and the `schedule_id` back-link on the spawned [Session] that
 * lets the app separate scheduled runs and drill into a live one.
 */
class ScheduleTest {

    @Test
    fun `schedule list decodes with kind=cadence and mode=fire-type`() {
        val json = """
            {"schedules":[
              {"id":"sch1","name":"nightly-audit","kind":"cron","mode":"agent",
               "enabled":true,"cron":"0 2 * * *","next_run":"2026-08-12T02:00:00Z",
               "last_run_session_id":"agent-9","last_run_status":"working",
               "repo":"acme/warden","prompt":"Run the linters"},
              {"id":"sch2","name":"dep-bump","kind":"cron","mode":"pipeline",
               "enabled":false,"cron":"0 6 * * 1"}
            ]}
        """.trimIndent()
        val list = WardenJson.decodeFromString(ScheduleList.serializer(), json).schedules
        assertEquals(2, list.size)

        val audit = list.first { it.id == "sch1" }
        assertTrue(audit.isRecurring)          // kind == cron
        assertFalse(audit.isPipeline)          // mode == agent
        assertEquals("0 2 * * *", audit.cron)
        assertEquals("agent-9", audit.lastRunSessionId)
        assertEquals("Run the linters", audit.taskLabel)

        val dep = list.first { it.id == "sch2" }
        assertTrue(dep.isRecurring)
        assertTrue(dep.isPipeline)             // mode == pipeline
        assertEquals("Runs a pipeline", dep.taskLabel)
        assertFalse(dep.enabled)
    }

    @Test
    fun `one-shot schedule reads as non-recurring`() {
        val json = """
            {"id":"sch3","kind":"at","mode":"agent","at":"2026-08-15T09:00:00Z"}
        """.trimIndent()
        val s = WardenJson.decodeFromString(Schedule.serializer(), json)
        assertFalse(s.isRecurring)
        assertEquals("2026-08-15T09:00:00Z", s.cadenceLabel)
        // displayName falls back to id when name is absent.
        assertEquals("sch3", s.displayName)
    }

    @Test
    fun `missing optional fields default and empty schedules list decodes`() {
        val empty = WardenJson.decodeFromString(ScheduleList.serializer(), """{"schedules":[]}""")
        assertTrue(empty.schedules.isEmpty())

        // A minimal object (only id) must not throw — every other field defaults.
        val bare = WardenJson.decodeFromString(Schedule.serializer(), """{"id":"x"}""")
        assertEquals("", bare.cron)
        assertEquals("", bare.lastRunStatus)
        assertTrue(bare.enabled) // defaults to true
    }

    @Test
    fun `schedule_id back-links a spawned session - absent stays a normal agent`() {
        val json = """
            [
              {"id":"agent-1","status":"working"},
              {"id":"agent-9","status":"working","schedule_id":"sch1","schedule_name":"nightly-audit"}
            ]
        """.trimIndent()
        val list = WardenJson.decodeFromString(ListSerializer(Session.serializer()), json)
        val plain = list.first { it.id == "agent-1" }
        val scheduled = list.first { it.id == "agent-9" }
        assertFalse(plain.isScheduled)
        assertTrue(scheduled.isScheduled)
        assertEquals("sch1", scheduled.scheduleId)
        assertEquals("nightly-audit", scheduled.scheduleName)
    }

    @Test
    fun `explicit null schedule_id coerces to blank, not scheduled`() {
        // The daemon omits schedule_id on manual agents; guard the null case too.
        val json = """{"id":"agent-2","status":"idle","schedule_id":null}"""
        val s = WardenJson.decodeFromString(Session.serializer(), json)
        assertEquals("", s.scheduleId)
        assertFalse(s.isScheduled)
    }
}
