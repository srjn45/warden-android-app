# Warden Android App — Design & Architecture Spec

**Date:** 2026-08-05
**Status:** Planning / design. No code yet. Supersedes the Android portions of
`docs/superpowers/specs/2026-06-10-warden-mobile-app-design.md` (which assumed
cross-platform React Native, excluded spawning, and referenced the pre-ship
`WARDEN_BIND_ADDR`/`7979` config — all outdated).

---

## 1. Goal & MVP scope

warden is mature and offers solid remote access (LAN, Tailscale, Cloudflare
tunnel). The web UI is awkward on phones. This spec plans a **dedicated,
Android-only native app** focused on driving a fleet from a phone.

**MVP features (only these):**

1. **Create / delete agents.**
2. **Open an agent** (view it, its live output).
3. **Interact with an agent** — send prompts, plus an on-screen key bar for keys
   the mobile soft-keyboard lacks (Esc, Tab, arrows, Ctrl-C, `|`, `~`, …).
4. **Raw terminal access** — run arbitrary commands on the host.

The app must let the user **enter the host address + passkey (bearer token)**
manually (with optional QR pairing as a convenience).

**Explicitly out of MVP:** push notifications, pipelines, metrics dashboards,
approvals UI, code editing, iOS, tablet layout. Named here so the first build
stays small and reviewable.

---

## 2. Key insight — the daemon already does the server-side work

Almost nothing new is needed on the warden side. Every MVP feature maps to an
**existing** authenticated `/api/v1` endpoint. Base URL `http://<host>:8765`.

| MVP feature | Endpoint | Protocol |
|---|---|---|
| List agents | `GET /api/v1/sessions` → `{sessions:[…]}` | REST |
| Live fleet updates | `GET /api/v1/events/stream` (full `{sessions:[…]}` snapshots + `:ping` every 25s) | SSE |
| Open one agent | `GET /api/v1/sessions/{id}` | REST |
| Create agent | `POST /api/v1/spawn` (`SpawnRequest`); `428`+`ConfirmationResponse` on memory-pressure gate → retry `force:true` | REST |
| Delete agent | `POST /api/v1/sessions/{id}/terminate` then `POST …/delete` (and `…/remove-worktree` if desired) | REST |
| Send a prompt | `POST /api/v1/sessions/{id}/input` (`InputRequest`) | REST |
| Transcript snapshot | `GET /api/v1/sessions/{id}/output?lines=N` | REST |
| **Interact / special keys / live output** | `GET /api/v1/sessions/{id}/attach` | **WebSocket** |
| **Raw host terminal** | `GET /api/v1/cockpit/attach` (3-pane TUI; master pane is a host shell/REPL) | **WebSocket** |
| Dir picker for spawn cwd | `GET /api/v1/fs/dirs` | REST |

Source of truth: `internal/daemon/apidocs/openapi.yaml`; streaming routes are
hand-registered in `internal/daemon/api.go` (excluded from codegen via
`internal/daemon/oapi/config.yaml`).

### 2.1 The terminal attach protocol (the heart of the app)

`internal/daemon/attach.go` bridges a real PTY (`tmux attach-session`) to a
WebSocket, forcing `TERM=xterm-256color`:

- **server → client:** binary frames = **raw xterm-256color terminal bytes.**
- **client → server, binary frame:** keystrokes written straight to the PTY.
- **client → server, text frame:** JSON `{"cols":N,"rows":M}` resize control.
- Cockpit route emits WS close code `4001` when its tmux session ends.

This single protocol powers three MVP requirements at once — **agent
interaction, the special-key bar, and raw terminal are all the same widget**: a
terminal emulator fed the byte stream, with a key bar that injects escape
sequences over the socket.

Because tmux holds the session **server-side**, the app can drop the socket when
backgrounded and re-attach later with scrollback intact — no need to fight
Android Doze with a persistent connection.

### 2.2 WS attach client contract (P1 reference)

Verified against `internal/daemon/attach.go` + `sse.go` (confirmed by the daemon
owner, 2026-08-06). Endpoint: `GET /api/v1/sessions/{id}/attach`. Capture the
exact behaviour here so P1 wiring doesn't need to re-read the daemon source.

- **Handshake / auth.** No WebSocket subprotocol — `websocket.Accept(w, r, nil)`;
  do **not** send `Sec-WebSocket-Protocol`. Auth is the `?token=` query param
  only (no header), same as SSE. OkHttp sends no `Origin`, so the server's
  cross-origin guard passes for a native client.
- **Framing.**
  - server → client: **always binary** = raw xterm-256color PTY bytes.
  - client → server, **binary** = keystrokes written straight to the PTY.
  - client → server, **text** = resize JSON `{"cols":N,"rows":M}` only. Any text
    that isn't valid resize JSON with non-zero dims is **silently ignored** (no
    reply) — never use text frames for anything else.
- **Frame sizes.** Server read limit is **1 MiB** per client binary frame — a
  larger single frame kills the connection, so **chunk large pastes to <1 MiB**.
  Server → client frames are ≤32 KiB (PTY read buffer); set OkHttp's read limit
  well above that.
- **Resize.** Not required before the stream starts (tmux attaches at its current
  size and streams immediately), **but** the server sets tmux `window-size
  latest`, so the most-recently-active client drives the pane size. **Send one
  resize text frame right after connect** (once the terminal view has measured
  cols/rows), and debounce-send on rotation / keyboard show — otherwise the pane
  inherits whatever the last attach (web UI / another device) set. Resize →
  `pty.Setsize` → `SIGWINCH`.
- **Keepalive.** The bridge sends no app-level pings and no WS ping frames
  (coder/websocket only auto-PONGs); an idle agent is pure silence. Enable
  OkHttp `pingInterval` ~20–30 s to hold NAT / Tailscale / Cloudflare paths open
  and detect half-open sockets — the WS analogue of the SSE `readTimeout=0` fix.
  The server PONGs automatically.
- **Close codes.** Agent attach (`signalSessionEnd=false`) never sends a special
  close code and uses `CloseNow()` on the normal detach path, so an **ordinary
  detach looks like an abnormal 1006-style close, not a clean 1000**. Treat *any*
  close as "detached, session still alive server-side" → offer reconnect; do
  **not** surface a non-1000 close as an error. Close code `4001`
  (`wsStatusCockpitEnded`) comes **only** from `/cockpit/attach` when the TUI is
  quit from inside — irrelevant until the raw-terminal/cockpit screen (~P3).
- **Reconnect.** Closing the socket detaches only this client; the tmux session
  keeps running with scrollback and repaints on re-attach. Reconnecting hits the
  **same live pane**, so the app can drop the socket on background and re-attach
  on resume without persisting the terminal buffer itself.

Note on the **SSE** stream (`sse.go`, same review): the server already dedupes
snapshots (`bytes.Equal`, emits only on real change) and always sends the initial
snapshot immediately on connect; `data:` frames are unnamed (no `event:`/`id:`),
so okhttp-sse delivers `type=null, id=null`. The client-side dedupe is
belt-and-suspenders and stays.

---

## 3. Auth & networking model (from code, not the old spec)

- **Scheme:** bearer token. `WARDEN_TOKEN` = 64 hex chars from
  `warden token generate`. Stateless constant-time compare; not persisted
  server-side. Optional `WARDEN_READONLY_TOKEN` (GETs + SSE only; 403 on writes
  and on `/attach`).
- **REST:** `Authorization: Bearer <token>`.
- **WS / SSE:** token as `?token=<token>` query param (headers can't be set on
  `EventSource`/WS upgrade).
- **Binding:** config key `addr`, default `127.0.0.1:8765`. Binding a
  non-loopback address **requires** `WARDEN_TOKEN` (daemon refuses otherwise).
- **`hostGuard`:** only enforced when NO token is set (loopback-only, anti-DNS-
  rebinding). Once a token is set it is bypassed — so a properly configured
  remote deployment never trips it.
- **No TLS in the daemon** (plain HTTP). Transport encryption comes from the
  network: **Tailscale (recommended)** or Cloudflare tunnel. No CORS — a non-
  issue for a native client anyway.

**Implication:** the app talks plain `http://` to a Tailscale/LAN IP, or
`https://` to a `*.ts.net` / tunnel domain. It must present the bearer token on
every transport.

---

## 4. Tech stack — Native Kotlin + Jetpack Compose

Chosen because the app is Android-only and terminal-first. (The old spec's
React Native pick optimized for iOS + web-code reuse — neither applies here, and
RN's terminal story, xterm.js in a WebView, is its weakest area.)

| Concern | Choice |
|---|---|
| UI | Jetpack Compose |
| **Terminal widget** | **Termux `terminal-view` + `terminal-emulator`** — battle-tested Android terminal that consumes the raw xterm-256color stream directly; its "extra keys" row is exactly the special-key requirement |
| REST | OkHttp + Retrofit (+ kotlinx.serialization / Moshi) |
| SSE | `okhttp-sse` (EventSource) |
| WebSocket | OkHttp `WebSocket` (binary + text frames) |
| Secure storage | Keystore-backed `EncryptedSharedPreferences` |
| QR pairing (optional) | CameraX + ML Kit barcode |
| Async | Coroutines + Flow |

**Trade-off accepted:** no code reuse with the TS web UI, no free iOS path.
If iOS ever matters, Flutter (`xterm.dart` + `flutter_pty`) is the fallback.

---

## 5. App architecture

```
┌─────────────────────────── Android app ───────────────────────────┐
│  UI (Compose)     Connect · AgentList · CreateAgent · Terminal     │
│  State            ViewModels · ConnectionStore · SessionCache      │
│  Transport  WardenClient                                           │
│    ├─ REST     OkHttp+Retrofit, Bearer interceptor → /api/v1/…      │
│    ├─ SSE      okhttp-sse                           → /events/stream│
│    └─ WS       OkHttp binary WebSocket              → …/attach      │
│  Secure store   EncryptedSharedPreferences (host + token)          │
└────────────────────────────────────────────────────────────────────┘
              │  http(s) over Tailscale / LAN / tunnel
              ▼   warden daemon :8765  (UNCHANGED for MVP)
```

Suggested modules: `:app` (UI), `:data` (WardenClient + DTOs), `:terminal`
(Termux view wrapper + WS bridge + key bar).

### Screens

1. **Connect** — host field (`http://100.x.y.z:8765` or
   `https://box.tailnet.ts.net`) + token field. "Test": `GET /healthz`, then an
   authed `GET /sessions`. Persist to Keystore. Optional "Scan QR"
   (`warden://host:port?token=…`). Support multiple saved hosts.
2. **Agent list** — fed by the SSE stream; status badge, model, age. Pull-to-
   refresh fallback (`GET /sessions`). "+" → create; swipe → delete (confirm).
3. **Create-agent sheet** — backend picker (the 8 backends), working dir via
   `GET /api/v1/fs/dirs` browse (no local FS), optional role/model, initial
   prompt. On `428`, show a "spawn anyway" confirm and resend with `force:true`.
4. **Agent detail / terminal** — Termux terminal bound to
   `…/sessions/{id}/attach` + the key bar; a quick-prompt input box that posts
   to `…/input`; a "terminate"/"delete" overflow.
5. **Raw host terminal** — same terminal widget on `…/cockpit/attach`.

### 5.1 On-screen key bar (the "buttons the keyboard lacks")

A horizontally scrollable row above the soft keyboard. Each key writes the
corresponding bytes over the WS. Ctrl/Alt are **sticky modifiers**.

| Key | Bytes sent |
|---|---|
| Esc | `0x1b` |
| Tab | `0x09` |
| Enter | `0x0d` |
| ↑ ↓ → ← | `ESC [ A/B/C/D` |
| Home / End | `ESC [ H` / `ESC [ F` |
| Ctrl-C / Ctrl-D / Ctrl-Z | `0x03` / `0x04` / `0x1a` |
| `Ctrl` (sticky) | next key → control byte (e.g. Ctrl+R → `0x12`) |
| `/  \|  ~  -  esc-shortcuts` | literal ASCII |

This is essentially Termux's extra-keys row, so most of it is a config away.

---

## 6. Gotchas to design around

- **Android cleartext policy:** `http://` to a Tailscale/LAN IP needs a
  `network_security_config` permitting cleartext to user hosts (or restrict the
  app to `https://`). Handle before first LAN test.
- **Reconnect model:** rely on tmux server-side persistence — reconnect + re-
  attach on resume rather than a battery-draining foreground service.
- **Read-only token:** detect via 403 on writes/attach and degrade gracefully.
- **Auth on WS/SSE is query-param only** — never try to set a header there.

---

## 7. Small daemon additions (post-MVP, optional)

None are required for MVP, but worth noting:

- **Dedicated throwaway host shell.** Today the only host-shell path is the
  *shared* cockpit TUI pane. A tiny "spawn a fresh PTY and attach" endpoint would
  give the app a private raw terminal instead of a shared one.
- **Push token registration** (`POST /push-token`) — only if/when notifications
  land (future phase).

---

## 8. Suggested build phases

- **P0 — Connect + list.** ✅ **Done.** Secure host/token entry, `/healthz` +
  `/sessions`, SSE-driven list. Proves auth + transport end to end.
- **P1 — Terminal.** ✅ **Built** (data path verified; on-device render pending an
  emulator). Termux emulator + WS binary bridge + resize + key bar against
  `…/attach`. See §8.2 for the as-built architecture.
- **P2 — Create / delete.** ✅ **Built.** Spawn sheet (backend/dir/role/prompt,
  `428` handling) + terminate/delete. See §8.3 for the as-built notes.
- **P3 — Raw host terminal** via `…/cockpit/attach`, and quick-prompt `/input`.
- **P4 — Polish.** Multiple saved hosts, QR pairing, reconnect/backoff.

### 8.1 P0 implementation notes (as built)

Concrete choices made during the P0 build; recorded here so later phases stay
consistent.

- **Toolchain:** single Gradle module `:app` (the design's split into
  `:app`/`:data`/`:terminal` is deferred until the terminal lands in P1). AGP
  8.6.1, Gradle 8.9, Kotlin 2.0.21, Compose BOM 2024.09. `minSdk 26`,
  `targetSdk 34`, `compileSdk 36` (only the `android-36` platform is installed
  on the build host; `android.suppressUnsupportedCompileSdk=36` silences the
  AGP warning).
- **Cleartext policy:** `network_security_config` permits cleartext broadly via
  `base-config` rather than a per-domain allowlist, because the host is entered
  at runtime and unknown at build time (design §6). `https://` hosts still use
  the system trust store. If we later want to tighten this, the app would need
  to rewrite the config per saved host — deferred as not worth it for MVP.
- **Storage:** host + token persist as a single encrypted JSON blob in a
  Keystore-backed `EncryptedSharedPreferences` file, modelled as a list of
  connections + an active label (multi-host-ready, though P0 UI drives one).
- **Networking:** `WardenClient` wraps Retrofit (kotlinx.serialization
  converter) for REST and okhttp-sse for the stream. Bearer token is a header
  interceptor on REST and a `?token=` query param on SSE. SSE read-timeout is
  disabled (0) so the ~25 s heartbeat doesn't kill an idle-but-healthy stream;
  identical consecutive `data:` frames are deduped before hitting the UI.
- **List behaviour:** rows keep the daemon's own order — we deliberately do
  **not** sort. (An earlier attention-first + `updated_at` sort made rows jump on
  every agent action, which is disorienting on a small screen.) Organisation is
  opt-in via **Group by**, mirroring the web cockpit's dimensions
  (`web/src/lib/group.ts`): Directory / Type / Status / Tag / Agent(`backend`),
  plus a **None** default (flat list). Grouping preserves server order too —
  groups appear first-seen, agents keep their incoming order within a group;
  `tag` fans a multi-tagged agent into each of its groups. Group headers are
  collapsible and the chosen mode persists (plain `SharedPreferences`, the mobile
  analogue of the web's `localStorage['warden.grouping']`). Pull-to-refresh falls
  back to `GET /sessions`; a stream indicator (live/connecting/offline) with a
  manual reconnect covers the disconnected state.
- **Verification:** JVM unit tests cover DTO decoding (with unknown daemon
  fields) and URL normalization; a gated live-daemon integration test
  (`LiveDaemonIntegrationTest`, env-var-gated, read-only) exercises the real
  REST + SSE path against a running daemon. No on-device render yet — no
  emulator/AVD was available on the build host.

### 8.2 P1 implementation notes (as built)

The terminal, built against the §2.2 contract. Key decision: **use Termux's
`terminal-emulator` + `terminal-view` as libraries but drive them from the
remote stream — no fork, no vendoring.**

- **Dependencies:** the Termux widgets aren't on Maven Central, so they come from
  JitPack (`com.termux.termux-app:terminal-view` + `:terminal-emulator`,
  **v0.118.0** — 0.118.1's AAR was never published). The JitPack repo is scoped
  by group in `settings.gradle.kts` so nothing else resolves through it. Both are
  Apache-2.0 (already credited in `NOTICE`).
- **Why not Termux's `TerminalView` as-is:** its `TerminalSession` is `final` and,
  on attach, spawns a **local** JNI subprocess (`updateSize → initializeEmulator →
  JNI.createSubprocess`). Useless for a remote PTY. But `TerminalEmulator` is a
  pure VT/xterm state machine with no process coupling, and `TerminalRenderer` is
  a pure canvas renderer that only reads the emulator. So we reuse those two and
  supply our own thin view + session.
- **`RemoteTerminalSession`** *is* the emulator's `TerminalOutput` (bytes the
  emulator emits → WS) and its `TerminalSessionClient` (only cursor-style + log
  hooks are ever called). Server→client bytes are fed in via `emulator.append()`.
- **`RemoteTerminalView`** (custom `View`) renders with Termux's `TerminalRenderer`
  and forwards input: hardware + soft keyboard (IME via `TYPE_NULL` + a
  `commitText` fallback), Ctrl/Alt semantics, special keys via `KeyHandler`,
  resize on layout, and touch scrollback. Text selection/mouse-tracking are
  deferred. Input goes straight to the socket; the screen repaints from the
  server's echo (the normal remote round-trip).
- **`WsTerminalTransport`** (OkHttp binary WS): binary in/out, resize as a text
  frame, pastes chunked under the 1 MiB limit, keepalive via the shared client's
  `pingInterval`. Per §2.2 *any* close after opening is a benign detach (offer
  reconnect, not an error); a failure before opening is a real error. The initial
  resize is (re)sent on every `Attached`.
- **Threading:** the emulator is single-threaded. `TerminalController` hops every
  inbound WS frame from OkHttp's reader thread to the main looper before touching
  the session, and owns the transport↔session↔view wiring + connection state.
- **Verification:** framing helpers are unit-tested; the live integration test
  gained a **read-only** WS check (`wsAttachReachesAttached`) that opens the real
  socket to a live session and asserts it reaches `Attached` — it sends no input
  and no resize, so attached agents are undisturbed. On-device glyph rendering is
  still unverified (no emulator/AVD on the host).

### 8.3 P2 implementation notes (as built)

Create + delete, mapped onto existing REST endpoints — **no daemon changes.**

- **Create (`POST /spawn`).** A `CreateAgentScreen` reachable from the list's `+`
  FAB. Fields: backend, working directory, name, role, model, initial prompt — all
  optional, free-form spawn (empty `type`). `SpawnRequest` is serialized by
  `WardenJson` (which does **not** encode defaults), so blank fields and
  `force=false` are omitted and the daemon applies its own defaults (empty backend
  = claude, absent cwd = home). On success (201) the sheet pops; the new agent
  appears via the live list.
- **The `428` spawn gate.** When the memory-pressure gate warns, `/spawn` returns
  `428` + a `ConfirmationResponse`. The body arrives via Retrofit's `errorBody()`;
  `WardenRepository.spawn` parses it and returns `SpawnOutcome.NeedsConfirmation`
  carrying the `Verdict`. The UI shows a "spawn anyway?" dialog (with the verdict's
  reason) that re-submits the identical request with `force = true`. The three
  outcomes — `Created` / `NeedsConfirmation` / `Failed(message)` — are a sealed
  type so the ViewModel handles each explicitly.
- **Working-dir browser.** A `ModalBottomSheet` driven by `GET /fs/dirs?path=` —
  no local filesystem access. Starts at the current cwd (or the daemon's home when
  blank), descends into `DirEntry` rows, walks up via the listing's `parent`
  (hidden at the fs root), and "Use this folder" picks the listing's `path`.
- **Backend picker.** The daemon has no `/backends` endpoint, so `Backend.ALL` is a
  static mirror of its backend registry (claude, aider, antigravity, codex, crush,
  cursor, goose, opencode), claude first. A future `GET /api/v1/backends` would
  make it self-updating. The **role** picker, by contrast, is live off `GET /roles`.
- **Delete.** A per-row overflow (⋮ → Delete) on the list and a Terminate/Delete
  overflow on the terminal screen. Delete runs `terminate` (best-effort — a 404 or
  already-dead agent is fine) → optional `remove-worktree` → `delete`, in that
  order so the tmux session is killed before the record is dropped. Any non-blank
  server warning (e.g. "may still be live") surfaces in a snackbar. The confirm
  dialog offers "also remove the git worktree & branch" only when the agent has a
  worktree. The row vanishes on the next SSE snapshot.
- **Verification.** Unit tests cover the spawn-request serialization contract
  (defaults omitted, `force` only when true), the `428`/delete body decoding, and
  the backend registry. The live integration test gained **read-only** checks for
  `GET /roles` and `GET /fs/dirs`. Spawn and delete are deliberately **never**
  exercised by automated tests — they mutate a real fleet — so those write paths
  are covered by the typed transport + manual use only. On-device rendering still
  pending an emulator/AVD.

### 8.4 Release signing (as built, v0.2.1)

Through v0.2.0 the release CI published the **debug** APK. The Android debug
keystore is generated fresh per machine, so each CI run produced a *different*
signing certificate — every release therefore failed to install over the
previous one (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, a signature mismatch). v0.2.1
fixes this with a proper release identity:

- **Stable keystore** — a 4096-bit RSA / PKCS12 keystore (`CN=Warden Android`,
  10 000-day validity) generated once and kept **outside the repo** at
  `~/.warden-android/warden-release.jks` (credentials alongside it, `chmod 600`).
  `.gitignore` blocks `*.jks`/`*.keystore` so it can never be committed. **Losing
  this keystore means the app can never be updated again** — back it up.
- **Signing config** (`app/build.gradle.kts`) reads the keystore path +
  passwords from `ANDROID_KEYSTORE_PATH` / `ANDROID_KEYSTORE_PASSWORD` /
  `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`. When no keystore is present
  (ordinary local builds, PR CI) the `release` build type falls back to debug
  signing, so nothing breaks — signing is applied only when the keystore exists.
- **CI** — the release workflow decodes `ANDROID_KEYSTORE_BASE64` (repo secret)
  to a temp file, builds `:app:assembleRelease`, and **verifies** with
  `apksigner` that the result is release-signed (not `Android Debug`) and
  non-debuggable before publishing `app-release.apk`. `versionName` comes from
  the tag (`v0.2.1` → `0.2.1`) and `versionCode` from the monotonic run number
  (`-PappVersionName`/`-PappVersionCode`); both default to `0.2.1`/`2` locally.
- **One-time cost:** moving from debug- to release-signing changes the cert, so
  any pre-0.2.1 build must be uninstalled once; every release from 0.2.1 onward
  installs in place.

Verified locally: `apksigner` reports `CN=Warden Android`, APK Signature Scheme
v2, non-debuggable, `versionCode 2` / `versionName 0.2.1`.

---

## 9. Repo placement

Separate concern from the Go release pipeline. Options: a top-level `android/`
directory in this repo, or a dedicated repo. Recommendation: **separate repo**
(distinct Gradle/Kotlin toolchain; keeps the GoReleaser flow and CI clean). The
daemon API is the stable contract between them; version it against
`openapi.yaml`.
