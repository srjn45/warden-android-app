package com.warden.android.ui.agents

import com.warden.android.data.model.Session

/**
 * How the agent list is bucketed. Mirrors the web cockpit's group-by dimensions
 * (`web/src/lib/group.ts`: dir / type / status / tag / backend), with [None]
 * added as the mobile default: a flat list in the daemon's own order. We do NOT
 * re-sort agents — the previous attention-first + updated_at ordering made rows
 * jump on every agent action, which read as confusing on a small screen. Groups
 * and the agents inside them keep the server snapshot's order (stable, no churn).
 *
 * [id] is the persisted key (kept identical to the web's values so a shared
 * preference reads the same); [label] is the chip/header text. "Agent" is the
 * web's label for the `backend` dimension (the AI driving the session).
 */
enum class GroupMode(val id: String, val label: String) {
    None("none", "None"),
    Directory("dir", "Directory"),
    Type("type", "Type"),
    Status("status", "Status"),
    Tag("tag", "Tag"),
    Agent("backend", "Agent");

    companion object {
        fun fromId(id: String?): GroupMode = entries.firstOrNull { it.id == id } ?: None
    }
}

/** A header + its agents. [label] is blank for the single [GroupMode.None] bucket. */
data class AgentGroup(
    val key: String,
    val label: String,
    val sessions: List<Session>,
)

// Sentinels matching the web (group.ts) so grouping behaves identically.
private const val UNKNOWN_DIR = "—"
private const val UNTYPED = "(untyped)"
private const val UNTAGGED = "(untagged)"
private const val DEFAULT_BACKEND = "claude"

/** Grouping key for [GroupMode.Directory]: repo wins, else workdir, else the sentinel. */
private fun sourceDir(s: Session): String =
    s.repo.ifBlank { s.workdir.ifBlank { UNKNOWN_DIR } }

/** Last path segment of a dir key, for the group header (mirrors web `baseName`). */
private fun baseName(dir: String): String {
    val trimmed = dir.trimEnd('/')
    val seg = trimmed.substringAfterLast('/')
    return seg.ifEmpty { dir }
}

/**
 * The key(s) an agent belongs to. Every mode yields exactly one key except
 * [GroupMode.Tag]: a multi-tagged agent appears in each of its tag groups, and
 * an untagged agent falls into [UNTAGGED].
 */
private fun keysFor(s: Session, mode: GroupMode): List<String> = when (mode) {
    GroupMode.Type -> listOf(s.type.ifBlank { UNTYPED })
    GroupMode.Agent -> listOf(s.backend.ifBlank { DEFAULT_BACKEND })
    GroupMode.Status -> listOf(s.status)
    GroupMode.Tag -> s.tags.filter { it.isNotBlank() }.ifEmpty { listOf(UNTAGGED) }
    GroupMode.Directory -> listOf(sourceDir(s))
    GroupMode.None -> listOf("")
}

private fun labelFor(key: String, mode: GroupMode): String = when (mode) {
    GroupMode.Directory -> baseName(key)
    GroupMode.Status -> key.replace('_', ' ')
    else -> key
}

/**
 * Buckets [sessions] on [mode], preserving the daemon's order: groups appear in
 * first-seen order and agents keep their incoming order within each group. For
 * [GroupMode.None] the whole list is returned as one unlabelled group.
 */
fun groupSessions(sessions: List<Session>, mode: GroupMode): List<AgentGroup> {
    if (mode == GroupMode.None) {
        return listOf(AgentGroup(key = "all", label = "", sessions = sessions))
    }
    // LinkedHashMap keeps first-seen key order; the lists keep append order.
    val buckets = LinkedHashMap<String, MutableList<Session>>()
    for (s in sessions) {
        for (k in keysFor(s, mode)) {
            buckets.getOrPut(k) { mutableListOf() }.add(s)
        }
    }
    return buckets.map { (key, ss) -> AgentGroup(key, labelFor(key, mode), ss) }
}
