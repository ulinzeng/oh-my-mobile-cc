# Test Fixtures — `shared/src/commonTest/resources/fixtures/`

This directory holds **NDJSON fixtures** of the Claude Code `stream-json` event stream.
They back the `ApprovalBridge` tests in `relay/` (Task **W1.4**) and the
`CCEvent.permissionRequest` round-trip tests in `shared/` (Task **W1.1**).

## Current state (2026-04-22)

| File | Kind | Status |
|---|---|---|
| `permission_bash_request.ndjson`          | Synthetic best-guess schema (W0) | ⚠ **WRONG schema — see below** |
| `real_captures/01-auto-deny-text-input.ndjson`         | Real `claude -p` capture, text input              | Real, redacted |
| `real_captures/02-auto-deny-streamjson-input.ndjson`   | Real `claude -p` capture, stream-json input       | Real, redacted |
| `real_captures/03-hook-bridge-approved.ndjson`         | Real `claude -p` capture with `PreToolUse:Bash` hook auto-approving | Real, redacted |

**The synthetic `permission_bash_request.ndjson` remains in place for now as a negative
reference — `CCEventTest.permissionRequest_synthetic` will deserialize it to confirm the
parser handles the *expected-but-wrong* shape for historical comparison, but all
production logic MUST drive off the real captures.**

## Why the synthetic schema was wrong

The Plan v2 W0 scaffolding assumed CC would emit a top-level `{"type":"permission_request", ...}`
event over stdout which the relay could intercept and bounce to mobile. **It doesn't.**

`claude -p --output-format stream-json --input-format stream-json` (CC 2.1.117) does NOT
expose permission gating over the stdout stream. Instead it enforces permissions
*in-process* and exposes two mechanisms to parent processes:

1. **`canUseTool` callback** — an in-process SDK hook (Python/TS `@anthropic-ai/claude-agent-sdk`).
   Not available to us because we are shelling out to the `claude` binary, not linking the SDK.
2. **`PreToolUse` hook** (shell command registered in `.claude/settings.json`).
   When a tool is about to run, CC spawns the hook, which can return
   `{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"allow|deny"}}`.
   Hook invocation **and** response are visible on stdout when `-p` is launched with
   `--include-hook-events`. **This is our real approval bridge.**

If no hook approves and no CLI rule pre-approves, CC auto-**denies** silently. Denied tool
calls surface only in the final `{"type":"result","permission_denials":[…]}` message; there
is NO stream event to intercept.

### Observed auto-deny payload (see `real_captures/01..02`)

```json
{
  "type": "result",
  "subtype": "success",
  "permission_denials": [
    {
      "tool_name": "Bash",
      "tool_use_id": "<tooluse>",
      "tool_input": { "command": "ls /tmp/...", "description": "..." }
    }
  ],
  "stop_reason": "end_turn",
  "duration_ms": 29948,
  ...
}
```

Field names are `tool_name` / `tool_input`, NOT `tool` / `input` as the synthetic
fixture assumed.

### Observed hook bridge flow (see `real_captures/03`)

Per `PreToolUse:Bash` invocation, CC emits:

```json
// 1) Request side — CC announces the hook is running
{
  "type": "system",
  "subtype": "hook_started",
  "hook_id": "<uuid>",
  "hook_name": "PreToolUse:Bash",
  "hook_event": "PreToolUse",
  "uuid": "<uuid>",
  "session_id": "<session>"
}

// 2) Response side — CC reports the hook's stdout + exit code
{
  "type": "system",
  "subtype": "hook_response",
  "hook_id": "<uuid>",
  "hook_name": "PreToolUse:Bash",
  "hook_event": "PreToolUse",
  "output": "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"permissionDecision\":\"allow\"}}",
  "stdout": "{\"continue\":true,\"suppressOutput\":true}",
  "stderr": "",
  "exit_code": 0,
  "outcome": "success",
  "uuid": "<uuid>"
}
```

The assistant message immediately before a `PreToolUse` hook contains the `tool_use` block with `name`, `id`, and `input` — that is what the hook script inspects via its stdin JSON payload.

## Implications for Plan v2 specs

| Spec | Delta required before W1.4 |
|---|---|
| `openspec/specs/protocol/spec.md`        | `CCEvent.PermissionRequest` must be replaced by `CCEvent.HookStarted(hook_event=PreToolUse)` parsing, plus `tool_use` extraction from the assistant message stream. |
| `openspec/specs/approval-inbox/spec.md`  | "relay intercepts `permission_request` and writes `permission_response` to stdin" is **incorrect**. The relay will instead (a) register itself as a `PreToolUse` hook that blocks on a UNIX socket, (b) marshal `tool_name + tool_input + session_id` to the mobile Inbox, (c) receive the decision, (d) exit 0 with `{"hookSpecificOutput":{"permissionDecision":"allow|deny", "permissionDecisionReason":"…"}}`. |
| `openspec/specs/protocol/spec.md`        | `WireMessage.ApprovalRequested` payload still applies, but the bridge's **source** is a hook invocation, not a stream event. |

See `docs/adr/0004-approval-bridge-via-pretooluse-hook.md` for the full architectural decision.

## How to re-capture (recipes)

All recipes run from any working directory that has a `claude` CLI installed. The
captures must be redacted before committing (see `redact_fixture.py` — TODO in W1.4).

### Recipe A — Auto-deny baseline (stream-json input)

```bash
mkfifo /tmp/req.fifo
( echo '{"type":"user","message":{"role":"user","content":[{"type":"text","text":"Please run the bash command: ls /tmp"}]}}'
  sleep 120 ) > /tmp/req.fifo &
timeout 90 claude -p \
  --output-format stream-json \
  --input-format stream-json \
  --permission-mode default \
  --allowed-tools Bash \
  --disable-slash-commands \
  --include-partial-messages \
  --verbose \
  < /tmp/req.fifo > auto-deny.ndjson
rm /tmp/req.fifo
```

### Recipe B — Hook-approved path (approval bridge proof)

Create `/tmp/settings.json`:
```json
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "Bash",
        "hooks": [ { "type": "command",
                     "command": "echo '{\"continue\":true,\"suppressOutput\":true}'" } ] }
    ]
  }
}
```
Then run Recipe A with `--include-hook-events --settings /tmp/settings.json` added.

## Gate for W1.4

`ApprovalBridgeTest` MUST load at least one of the `real_captures/*.ndjson` files, and MUST fail if only `permission_bash_request.ndjson` (synthetic) is present.
