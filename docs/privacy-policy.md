# Privacy Policy — Warden (Android)

_Last updated: 6 August 2026_

Warden is a native Android client for a **self-hosted [warden](https://github.com/srjn45/warden) daemon**.
It is a "bring-your-own-server" tool: the app only ever talks to a warden daemon
that **you** run and configure. This policy explains what the app does and does
not do with your information.

## Summary

- **We do not collect, transmit, or sell any personal data.**
- The app contains **no analytics, no advertising, and no third-party tracking SDKs.**
- Everything you enter stays **on your device** or goes **only to the server you point it at.**

## What the app stores on your device

To connect to your daemon, the app stores the following **locally on your device only**:

- The **host address(es)** you enter (e.g. a LAN IP, Tailscale address, or tunnel domain).
- The **access token** for each host.
- Your in-app **preferences** (such as the agent grouping mode).

Access tokens are stored using Android's encrypted storage (Jetpack Security
`EncryptedSharedPreferences`). This data never leaves your device except as part
of the authenticated requests you make to your own daemon (below). Uninstalling
the app deletes it.

## Data sent over the network

When you connect, the app communicates **exclusively with the warden daemon at
the address you configured**. This traffic carries your access token and the
commands you issue (listing agents, opening a terminal, creating/deleting agents,
controlling pipelines, etc.). It is sent directly to your server and to **no one
else** — not to the developer, and not to any third party.

Transport security (HTTPS/TLS) is provided by your own network setup — for
example a Tailscale connection or a Cloudflare tunnel. The app permits plain HTTP
so that direct LAN/Tailscale addresses work; you are responsible for the security
of the network path to your daemon.

## Data we collect

**None.** The developer operates no servers that receive your data, runs no
analytics, and has no ability to see the hosts, tokens, or commands you use.

## Permissions

The app requests only:

- `INTERNET` and `ACCESS_NETWORK_STATE` — required to reach your daemon.

It does not request access to contacts, location, camera, microphone, files, or
any other sensitive resource.

## Children

The app is a developer tool and is not directed to children under 13.

## Changes to this policy

If this policy changes, the updated version will be published at this same URL
with a new "Last updated" date.

## Contact

Questions about this policy: **srajanpathak45@gmail.com**
