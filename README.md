# warden-android-app

A dedicated **Android** app for driving a [warden](https://github.com/srjn45/warden)
fleet of coding agents from a phone. The warden web UI is awkward on mobile; this
app is a native, terminal-first client that talks directly to the warden daemon's
remote API (LAN, Tailscale, or Cloudflare tunnel).

> **Status: P1 built.** Connect + live SSE-driven agent list (P0), plus an
> interactive **terminal** (P1): tap an agent to attach to its tmux PTY over a
> binary WebSocket, with a soft-keyboard-friendly extra-keys bar and resize. The
> terminal reuses Termux's emulator + renderer driven from the remote stream (no
> local subprocess, no fork). The whole data path — REST, SSE, and WS attach — is
> verified end-to-end against a running daemon; on-device glyph rendering awaits
> an emulator/AVD. See [`docs/design.md`](docs/design.md) for the full spec and
> the as-built notes (§8.1 P0, §8.2 P1).
>
> **Build:** `./gradlew :app:assembleDebug` (Android SDK with the `android-36`
> platform + JDK 17 required).

## MVP scope

- **Create / delete agents.**
- **Open an agent** and watch its live output.
- **Interact** — send prompts, plus an on-screen key bar for keys the mobile
  soft-keyboard lacks (Esc, Tab, arrows, Ctrl-C, `|`, `~`, …).
- **Raw terminal** — run arbitrary commands on the host.

The app connects with a **host address + bearer token** (manual entry, optional
QR pairing). It talks directly to the daemon — no relay, no cloud backend.

## How it connects

Everything maps to the daemon's existing `/api/v1` surface — **no server-side
changes are needed for the MVP**:

- REST for agent CRUD (`/sessions`, `/spawn`, `/sessions/{id}/input`, …)
- SSE (`/events/stream`) for the live agent list
- WebSocket (`/sessions/{id}/attach`, `/cockpit/attach`) for interactive
  terminals — the daemon bridges a real tmux PTY, streaming raw
  `xterm-256color` bytes both ways. Agent interaction, the special-key bar, and
  raw terminal are all the same terminal widget.

Auth is a bearer token: `Authorization: Bearer <token>` on REST, `?token=<t>` on
WS/SSE. The daemon is plain HTTP — use Tailscale or a Cloudflare tunnel for
transport encryption.

## Tech stack

Native **Kotlin + Jetpack Compose**, using **Termux `terminal-view` /
`terminal-emulator`** for the terminal (they consume the daemon's raw PTY stream
directly, and the extra-keys row is exactly the on-screen special-key
requirement), **OkHttp** (REST + `okhttp-sse` + binary WebSocket), and
Keystore-backed `EncryptedSharedPreferences` for the host + token.

## Contract

The daemon's `openapi.yaml` (in the warden repo) is the versioned contract
between this app and warden.
