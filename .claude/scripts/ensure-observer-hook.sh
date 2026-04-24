#!/usr/bin/env bash
# ensure-observer-hook.sh — READ-ONLY verifier for ECC observer hook.
#
# Checks whether $HOME/.claude/settings.json references the ECC observe.sh
# hook. Prints exactly one of:
#   PASS: observer hook registered (found: <path>)
#   NEEDS_USER_ACTION: ~/.claude/settings.json missing
#   NEEDS_USER_ACTION: add ECC observe.sh hook to ~/.claude/settings.json (see .claude/docs/ecc-hooks.md)
#
# Always exits 0 — this is a notification, not a gate.
# HOME is honored for test isolation.
#
# NEVER writes to $HOME/.claude/ — this script is strictly read-only.
set -u

settings="${HOME}/.claude/settings.json"

if [[ ! -f "$settings" ]]; then
  echo "NEEDS_USER_ACTION: ~/.claude/settings.json missing"
  exit 0
fi

found=""
if command -v jq >/dev/null 2>&1; then
  # Walk any value containing observe.sh
  found="$(jq -r '.. | strings | select(test("observe\\.sh"))' "$settings" 2>/dev/null | head -n1 || true)"
fi

# Fallback (or when jq produced nothing): raw grep.
if [[ -z "$found" ]]; then
  found="$(grep -o '[^"[:space:]]*observe\.sh' "$settings" 2>/dev/null | head -n1 || true)"
fi

if [[ -n "$found" ]]; then
  echo "PASS: observer hook registered (found: $found)"
  exit 0
fi

echo "NEEDS_USER_ACTION: add ECC observe.sh hook to ~/.claude/settings.json (see .claude/docs/ecc-hooks.md)"
exit 0
