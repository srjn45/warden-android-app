# warden-android-app

A dedicated **Android** app for driving a [warden](https://github.com/srjn45/warden)
fleet of coding agents from a phone. The warden web UI is awkward on mobile; this
app is a native, terminal-first client that talks directly to the warden daemon's
remote API (LAN, Tailscale, or Cloudflare tunnel).

> **Status: planning.** No app code yet — this repo currently holds the design &
> architecture spec. See [`docs/design.md`](docs/design.md).

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
