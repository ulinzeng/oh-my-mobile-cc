# oh-my-mobile-cc

> Drive your desktop Claude Code from your phone — with an in-app approval inbox,
> not just a view-only mirror.

**Status**: W0 bootstrap — not yet runnable.

## Problem

Claude Code (CC) is a first-class desktop agent, but its permission prompts and
`AskUserQuestion` events **block on a local TTY**. The moment you walk away from
the laptop, Auto Mode stalls waiting for a keystroke. The official Remote Control
is Pro/Max-only, single-connection, and its protocol is not publicly documented,
so there is no sanctioned way to approve a `Bash rm ...` prompt from a phone.

## Solution

`oh-my-mobile-cc` is a **Kotlin Multiplatform** client for iOS and Android that
talks to a **local desktop relay** you run yourself. The relay wraps:

```
claude -p --output-format stream-json --input-format stream-json
```

and exposes the CC event stream to the phone over a WebSocket carried inside
your **Tailscale** tailnet — no public server, no custom VPN SDK, no APNs.

The phone ships with a dedicated **Approval Inbox** tab: every time CC emits a
`permission_request` or `AskUserQuestion`, a card appears on your phone with
`Allow Once` / `Deny` / `Customize` actions. Responses are written back into
CC's stdin, so Auto Mode keeps running while you are on the move.

## Architecture

```
┌─────────────────────────────┐        ┌──────────────────────────┐
│  iOS / Android              │  WSS   │  Desktop Relay           │
│  ┌────────┬────────┬──────┐ │ TLS1.3 │  ┌────────────────────┐  │
│  │ Chat   │ Term   │Files │─┼────────┼──│ claude -p stream-  │  │
│  ├────────┴────────┴──────┤ │  over  │  │ json --permission- │  │
│  │ Inbox                  │◀┼───────▶│  │ mode default       │  │
│  │  [•3] pending approvals│ │Tailscl │  └────────────────────┘  │
│  └────────────────────────┘ │        │  (local CC)              │
└─────────────────────────────┘        └──────────────────────────┘
```

The shared Kotlin core follows **Ports & Adapters + feature-first** — `core/`
has no framework dependency, `features/` orchestrates use cases, `adapters/`
are the only places that touch Ktor / SqlDelight / pty4j.

## Features

| Feature          | Status          | Notes                                        |
| ---------------- | --------------- | -------------------------------------------- |
| Chat             | [planned]       | Full CC conversation, streamed from relay.   |
| Terminal viewer  | [planned]       | Compose/SwiftUI self-painted ANSI grid (W3). |
| Approval Inbox   | [planned]       | Allow / Deny / Customize, 10-min timeout.    |
| File browser     | [planned]       | Read + small-file edit over relay (<2MB).    |
| Pairing          | [planned]       | 6-digit code + Ed25519, no hardcoded tokens. |

## Non-goals

Explicitly out of scope for v1 (see `docs/adr/0003-inbox-vs-apns.md`):

- APNs / Firebase push notifications
- iOS long-running background tasks (`BGProcessingTask`)
- Computer Use / VNC-style remote desktop
- A self-hosted public relay server
- Multi-user collaboration on the same CC session
- Large-file editing (>2MB)
- Mac Catalyst / public App Store release

## Module Layout

| Module        | Target         | Responsibility                                                   |
| ------------- | -------------- | ---------------------------------------------------------------- |
| `shared/`     | KMP (JVM/iOS/Android) | `core/` protocol + domain, `ports/`, `adapters/`, `features/`, `ui/` (Compose Multiplatform for Android/desktop). |
| `androidApp/` | Android        | Thin shell: Compose host + Foreground Service for Inbox polling. |
| `iosApp/`     | iOS            | SwiftUI shell; consumes `shared` framework via SKIE.             |
| `relay/`      | JVM (desktop)  | Wraps `claude -p`, owns `ApprovalBridge`, `Pty`, `FsBridge`, `RelayServer`, `PairingService`. |

## Prerequisites

- JDK 17 (the Kotlin JVM toolchain target)
- Android SDK 34+ (for `androidApp`)
- Xcode 15+ (for `iosApp`, TestFlight path)
- Node 20+ (only for the OpenSpec CLI — `@fission-ai/openspec` 1.3.1+)
- [Tailscale](https://tailscale.com) logged in on **both** the phone and the
  desktop running the relay
- The native **`claude` CLI** installed and working on the desktop

## Quick start (W0 scaffolding — not yet functional)

At W0 there is no end-user build; these commands only verify the scaffolding:

```bash
# Sanity-check the shared KMP module compiles on JVM
./gradlew :shared:compileKotlinJvm

# Browse the OpenSpec truth source
openspec list

# Start the desktop relay (no transport wired yet)
./gradlew :relay:run
```

Once W1 lands, the real flow will be: start the relay, pair the phone, open
the Inbox tab.

## Documentation map

| Pointer                      | Audience              | Contents                                           |
| ---------------------------- | --------------------- | -------------------------------------------------- |
| `README.zh-CN.md`            | Chinese-reading users | 本 README 的中文镜像                               |
| `AGENTS.md`                  | AI agents             | Agent workflow, navigation map, TDD discipline     |
| `openspec/project.md`        | Everyone              | Tech stack + architectural constraints (truth)     |
| `openspec/specs/**`          | Everyone              | Per-capability specs (protocol, approval-inbox, …) |
| `openspec/changes/<id>/`     | Everyone              | Active change proposals before they become spec    |
| `docs/adr/`                  | Everyone              | Architecture decisions (KMP, Tailscale, Inbox)     |
| `docs/skills/`               | AI agents             | Progressive-load skills, triggered by keywords     |
| `.claude/PRPs/plans/`        | Maintainers           | The v2 implementation plan driving W0–W4           |

## License

**TBD — not yet decided. Do not redistribute.**
