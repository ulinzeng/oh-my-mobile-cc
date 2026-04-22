# Test Fixtures — ⚠ SYNTHETIC, MUST REPLACE BEFORE W1.4

This directory holds **NDJSON fixtures** of the Claude Code `stream-json` event stream.
The `ApprovalBridge` tests in `relay/` (Task **W1.4**) and the `CCEvent.permissionRequest`
round-trip tests in `shared/` (Task **W1.1**) must run against **real captures** of the
CC CLI's permission events.

## Current state

| File | Kind | Status |
|---|---|---|
| `permission_bash_request.ndjson` | Synthetic best-guess schema | ⚠ **NOT A REAL CAPTURE** |

The synthetic fixture encodes our **assumed** schema based on the plan's description:

```json
{"type":"permission_request","request_id":"...","session_id":"...","tool":"Bash",
 "input":{"command":"...","description":"..."},"reason":"...","proposed_at":...}
```

This assumption may be **wrong**. The `stream-json` permission event schema is not
publicly documented (as of 2026-04-22) and may use different field names
(`tool_name` vs `tool`, `tool_input` vs `input`, etc.).

## How to replace with a real capture (W0.6 hard prerequisite for W1.4)

On a machine with `claude` CLI installed (any OS where CC runs):

```bash
# 1. Start a fresh session that will trigger a permission prompt.
claude --bare -p \
  --output-format stream-json \
  --input-format  stream-json \
  --permission-mode default \
  --include-partial-messages \
  -p "use Bash tool to run: ls /tmp" \
  > shared/src/commonTest/resources/fixtures/permission_bash_request.ndjson

# 2. Do NOT respond. The capture should contain the permission_request event.
#    Press Ctrl+C after 2–3 seconds to end the capture.

# 3. Inspect:
cat shared/src/commonTest/resources/fixtures/permission_bash_request.ndjson | jq 'select(.type=="permission_request")'

# 4. If field names differ from the synthetic fixture, update:
#    - shared/.../core/protocol/CCEvent.kt  (@SerialName values)
#    - shared/.../features/approval/*       (deserialization paths)
#    - remove the "SYNTHETIC" warning above and this file's title
```

## Why defer the capture?

The Plan v2 author accepted this deferral explicitly to unblock W0 docs + infra.
**W1.4 implementation MUST refuse to start if this fixture is still synthetic** —
enforce that in the first commit of W1.4 with a test-time assertion.
