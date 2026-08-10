package com.warden.android.data.demo

import com.warden.android.data.model.Backend
import com.warden.android.data.model.BackendInfo
import com.warden.android.data.model.ContextState
import com.warden.android.data.model.DirEntry
import com.warden.android.data.model.DirListing
import com.warden.android.data.model.Pipeline
import com.warden.android.data.model.PipelineJob
import com.warden.android.data.model.PipelineStatus
import com.warden.android.data.model.Kind
import com.warden.android.data.model.RoleInfo
import com.warden.android.data.model.Session
import com.warden.android.data.model.Status

/**
 * Canned fixtures backing the offline **demo mode** (see [DemoTransport]). The
 * data is deliberately varied — every agent status, a multi-job pipeline, an
 * orphaned agent to exercise Restore — so the demo doubles as a faithful preview
 * and as the source for Play Store screenshots. Timestamps are fixed strings;
 * nothing here touches the clock or the network.
 */
object DemoData {

    private const val REPO = "acme/warden"

    /** A pristine fleet snapshot. A fresh copy is taken on each demo activation. */
    fun sessions(): List<Session> = listOf(
        Session(
            id = "demo-a1",
            name = "api-refactor",
            status = Status.WORKING,
            repo = REPO,
            branch = "agent/api-refactor",
            role = "backend",
            backend = "claude",
            model = "claude-opus-4-8",
            subject = "Extract the session store behind an interface",
            lastPaneExcerpt = "Editing WardenRepository.kt … running :app:testDebugUnitTest",
            contextTokens = 48_200,
            contextState = ContextState.OK,
            createdAt = "2026-08-06T09:12:00Z",
            updatedAt = "2026-08-06T14:31:00Z",
        ),
        Session(
            id = "demo-a2",
            name = "flaky-test-hunt",
            status = Status.WAITING_FOR_INPUT,
            repo = REPO,
            branch = "agent/flaky-test-hunt",
            role = "tester",
            backend = "claude",
            model = "claude-sonnet-5",
            subject = "Reproduce the intermittent SSE reconnect failure",
            lastPaneExcerpt = "I can retry with a longer read timeout — proceed? (y/n)",
            contextTokens = 71_900,
            contextState = ContextState.WARNING,
            createdAt = "2026-08-06T10:02:00Z",
            updatedAt = "2026-08-06T14:28:00Z",
        ),
        Session(
            id = "demo-a3",
            name = "docs-sweep",
            status = Status.IDLE,
            repo = REPO,
            branch = "agent/docs-sweep",
            role = "docs",
            backend = "codex",
            model = "gpt-5-codex",
            subject = "Update README for the drawer-first navigation",
            lastPaneExcerpt = "Waiting for the next instruction.",
            contextTokens = 22_400,
            contextState = ContextState.OK,
            createdAt = "2026-08-06T08:40:00Z",
            updatedAt = "2026-08-06T13:05:00Z",
        ),
        Session(
            id = "demo-a4",
            name = "release-notes",
            status = Status.DONE,
            exitCode = 0,
            repo = REPO,
            branch = "agent/release-notes",
            role = "general",
            backend = "claude",
            subject = "Draft the v0.9.0 changelog",
            lastPaneExcerpt = "Done. Wrote CHANGELOG.md and opened PR #142.",
            contextTokens = 15_100,
            contextState = ContextState.OK,
            createdAt = "2026-08-06T07:55:00Z",
            updatedAt = "2026-08-06T09:20:00Z",
        ),
        Session(
            id = "demo-a5",
            name = "db-migration",
            status = Status.ERRORED,
            exitCode = 1,
            repo = REPO,
            branch = "agent/db-migration",
            role = "backend",
            backend = "claude",
            subject = "Add the pipeline_runs table",
            lastPaneExcerpt = "error: migration 014 failed — column already exists",
            contextTokens = 33_700,
            contextState = ContextState.OK,
            createdAt = "2026-08-06T11:15:00Z",
            updatedAt = "2026-08-06T12:41:00Z",
        ),
        Session(
            id = "demo-a6",
            name = "cache-layer",
            status = Status.ORPHANED,
            repo = REPO,
            branch = "agent/cache-layer",
            role = "backend",
            backend = "claude",
            subject = "Add an LRU cache in front of the roles endpoint",
            lastPaneExcerpt = "(tmux session lost — restore to resume where it left off)",
            contextTokens = 29_050,
            contextState = ContextState.OK,
            createdAt = "2026-08-05T18:30:00Z",
            updatedAt = "2026-08-06T06:10:00Z",
        ),
        Session(
            id = "demo-a7",
            name = "ui-polish",
            status = Status.WORKING,
            repo = REPO,
            branch = "agent/ui-polish",
            role = "frontend",
            backend = "claude",
            model = "claude-opus-4-8",
            subject = "Endpoints for the backend registry pipeline",
            lastPaneExcerpt = "Wiring GET /api/v1/backends into the create sheet …",
            contextTokens = 54_800,
            contextState = ContextState.OK,
            pipelineId = "demo-p1",
            jobId = "endpoints",
            createdAt = "2026-08-06T12:00:00Z",
            updatedAt = "2026-08-06T14:33:00Z",
        ),
        Session(
            id = "demo-a8",
            name = "perf-audit",
            status = Status.RATE_LIMITED,
            repo = REPO,
            branch = "agent/perf-audit",
            role = "general",
            backend = "claude",
            subject = "Profile the SSE decode hot path",
            lastPaneExcerpt = "rate limited by the provider — retrying in 40s",
            contextTokens = 60_300,
            contextState = ContextState.WARNING,
            createdAt = "2026-08-06T13:20:00Z",
            updatedAt = "2026-08-06T14:30:00Z",
        ),
        // Terminals — first-class sessions (kind=terminal), surfaced on the
        // Terminals screen and filtered out of the Agents list.
        Session(
            id = "demo-t1",
            name = "shell — warden",
            kind = Kind.TERMINAL,
            status = Status.WORKING,
            workdir = "/home/dev/dev/warden",
            subject = "~/dev/warden",
            lastPaneExcerpt = "dev@warden:~/dev/warden$ git status",
            createdAt = "2026-08-06T13:50:00Z",
            updatedAt = "2026-08-06T14:32:00Z",
        ),
        Session(
            id = "demo-t2",
            name = "shell — android-app",
            kind = Kind.TERMINAL,
            status = Status.WORKING,
            workdir = "/home/dev/dev/warden-android-app",
            subject = "~/dev/warden-android-app",
            lastPaneExcerpt = "dev@warden:~/dev/warden-android-app$ ./gradlew assembleDebug",
            createdAt = "2026-08-06T14:05:00Z",
            updatedAt = "2026-08-06T14:29:00Z",
        ),
    )

    /** A pristine pipeline snapshot. A fresh copy is taken on each demo activation. */
    fun pipelines(): List<Pipeline> = listOf(
        Pipeline(
            id = "demo-p1",
            name = "backend-registry",
            repo = REPO,
            status = PipelineStatus.RUNNING,
            tags = listOf("feature"),
            jobs = listOf(
                PipelineJob(
                    id = "scaffold",
                    prompt = "Scaffold the /backends endpoint + model",
                    status = "done",
                    branch = "agent/backend-registry-scaffold",
                ),
                PipelineJob(
                    id = "endpoints",
                    prompt = "Wire the endpoint into the create sheet",
                    dependsOn = listOf("scaffold"),
                    sessionId = "demo-a7",
                    status = "running",
                    branch = "agent/ui-polish",
                ),
                PipelineJob(
                    id = "tests",
                    prompt = "Unit-test the backend fallback path",
                    dependsOn = listOf("endpoints"),
                    status = "pending",
                ),
                PipelineJob(
                    id = "docs",
                    prompt = "Document the backends picker",
                    dependsOn = listOf("endpoints"),
                    status = "pending",
                ),
            ),
        ),
        Pipeline(
            id = "demo-p2",
            name = "nightly-audit",
            repo = REPO,
            status = PipelineStatus.DONE,
            tags = listOf("scheduled"),
            jobs = listOf(
                PipelineJob(id = "lint", prompt = "Run the linters", status = "done"),
                PipelineJob(
                    id = "deps",
                    prompt = "Check for outdated dependencies",
                    dependsOn = listOf("lint"),
                    status = "done",
                ),
            ),
        ),
    )

    /** Built-in roles for the spawn sheet (general first, matching the daemon). */
    fun roles(): List<RoleInfo> = listOf(
        RoleInfo("general", "Free-form agent with no specialization"),
        RoleInfo("backend", "Server, API, and data-layer work"),
        RoleInfo("frontend", "UI and client-side work"),
        RoleInfo("tester", "Writes and runs tests, hunts regressions"),
        RoleInfo("reviewer", "Reviews diffs for correctness and style"),
        RoleInfo("docs", "Documentation and changelogs"),
    )

    /** Backends for the spawn sheet — the static registry, all available. */
    fun backends(): List<BackendInfo> = Backend.staticInfos()

    /** A small fake filesystem for the working-dir browser. */
    fun dirListing(path: String?): DirListing = when (path?.trimEnd('/')) {
        null, "" -> DirListing(
            path = "/home/dev",
            parent = "",
            entries = listOf(
                DirEntry("dev", "/home/dev/dev"),
                DirEntry("projects", "/home/dev/projects"),
                DirEntry(".config", "/home/dev/.config"),
            ),
        )
        "/home/dev/dev" -> DirListing(
            path = "/home/dev/dev",
            parent = "/home/dev",
            entries = listOf(
                DirEntry("warden", "/home/dev/dev/warden"),
                DirEntry("warden-android-app", "/home/dev/dev/warden-android-app"),
            ),
        )
        else -> DirListing(path = path.orEmpty(), parent = "/home/dev", entries = emptyList())
    }

    /** The scripted terminal transcript shown when attaching to a demo agent. */
    fun terminalTranscript(sessionName: String): String {
        val esc = ""
        fun bold(s: String) = "$esc[1m$s$esc[0m"
        fun green(s: String) = "$esc[32m$s$esc[0m"
        fun cyan(s: String) = "$esc[36m$s$esc[0m"
        fun dim(s: String) = "$esc[2m$s$esc[0m"
        return buildString {
            append("\r\n")
            append(dim("── attached to ") + cyan(sessionName) + dim(" (demo) ──") + "\r\n\r\n")
            append(bold("warden") + " agent session\r\n")
            append(green("✓") + " worktree ready\r\n")
            append(green("✓") + " tmux pane live\r\n\r\n")
            append("> Extract the session store behind an interface\r\n\r\n")
            append(dim("This is a read-only demo terminal. Type to see local echo;\r\n"))
            append(dim("connect a real warden daemon for a live PTY.\r\n\r\n"))
            append(cyan("dev@warden") + ":" + "~/dev/warden$ ")
        }
    }
}
