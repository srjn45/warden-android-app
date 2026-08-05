package com.warden.android.ui.agents

import com.warden.android.data.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentGroupingTest {

    private fun session(
        id: String,
        type: String = "",
        status: String = "",
        repo: String = "",
        workdir: String = "",
        tags: List<String> = emptyList(),
        backend: String = "",
    ) = Session(
        id = id,
        type = type,
        status = status,
        repo = repo,
        workdir = workdir,
        tags = tags,
        backend = backend,
    )

    @Test
    fun `none returns one bucket preserving server order`() {
        val agents = listOf(session("c"), session("a"), session("b"))
        val groups = groupSessions(agents, GroupMode.None)

        assertEquals(1, groups.size)
        // Order is exactly as received — no sorting.
        assertEquals(listOf("c", "a", "b"), groups.single().sessions.map { it.id })
    }

    @Test
    fun `grouping keeps first-seen group order and in-group server order`() {
        val agents = listOf(
            session("1", status = "working"),
            session("2", status = "idle"),
            session("3", status = "working"),
        )
        val groups = groupSessions(agents, GroupMode.Status)

        // "working" seen before "idle" → that group order; within, 1 before 3.
        assertEquals(listOf("working", "idle"), groups.map { it.key })
        assertEquals(listOf("1", "3"), groups[0].sessions.map { it.id })
        assertEquals(listOf("2"), groups[1].sessions.map { it.id })
    }

    @Test
    fun `status label humanises underscores`() {
        val groups = groupSessions(listOf(session("1", status = "waiting_for_input")), GroupMode.Status)
        assertEquals("waiting for input", groups.single().label)
    }

    @Test
    fun `directory prefers repo then workdir then sentinel, header is basename`() {
        val agents = listOf(
            session("1", repo = "/home/u/dev/warden"),
            session("2", workdir = "/home/u/dev/other"),
            session("3"),
        )
        val groups = groupSessions(agents, GroupMode.Directory).associateBy { it.key }

        assertEquals("warden", groups.getValue("/home/u/dev/warden").label)
        assertEquals("other", groups.getValue("/home/u/dev/other").label)
        assertEquals(1, groups.getValue("—").sessions.size)
    }

    @Test
    fun `tags fan out a multi-tagged agent and bucket untagged`() {
        val agents = listOf(
            session("1", tags = listOf("infra", "urgent")),
            session("2", tags = emptyList()),
        )
        val groups = groupSessions(agents, GroupMode.Tag).associateBy { it.key }

        assertEquals(setOf("infra", "urgent", "(untagged)"), groups.keys)
        assertEquals(listOf("1"), groups.getValue("infra").sessions.map { it.id })
        assertEquals(listOf("1"), groups.getValue("urgent").sessions.map { it.id })
        assertEquals(listOf("2"), groups.getValue("(untagged)").sessions.map { it.id })
    }

    @Test
    fun `empty backend groups under claude default and empty type is untyped`() {
        val byBackend = groupSessions(listOf(session("1", backend = "")), GroupMode.Agent)
        assertEquals("claude", byBackend.single().key)

        val byType = groupSessions(listOf(session("1", type = "")), GroupMode.Type)
        assertEquals("(untyped)", byType.single().key)
    }
}
