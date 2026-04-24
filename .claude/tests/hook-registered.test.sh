#!/usr/bin/env bash
# Test: .claude/scripts/ensure-observer-hook.sh must print PASS when
# ~/.claude/settings.json contains observe.sh, and NEEDS_USER_ACTION otherwise.
# Uses HOME override to isolate from the real user-global settings.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERIFIER="$REPO_ROOT/.claude/scripts/ensure-observer-hook.sh"
FIXTURES="$REPO_ROOT/.claude/tests/fixtures"

if [[ ! -x "$VERIFIER" ]]; then
  echo "FAIL: verifier not found or not executable at $VERIFIER"
  exit 1
fi

fail=0

# --- POSITIVE: fixture contains observe.sh -> PASS, exit 0 ---
pos_home="$(mktemp -d)"
mkdir -p "$pos_home/.claude"
cp "$FIXTURES/settings-with-observer.json" "$pos_home/.claude/settings.json"
set +e
pos_out="$(HOME="$pos_home" "$VERIFIER" 2>&1)"
pos_rc=$?
set -e
if [[ $pos_rc -eq 0 && "$pos_out" == *"PASS"* ]]; then
  echo "POSITIVE ok: $pos_out"
else
  echo "POSITIVE FAIL (rc=$pos_rc): $pos_out"
  fail=1
fi
rm -rf "$pos_home"

# --- NEGATIVE: fixture lacks observe.sh -> NEEDS_USER_ACTION, exit 0 ---
neg_home="$(mktemp -d)"
mkdir -p "$neg_home/.claude"
cp "$FIXTURES/settings-without-observer.json" "$neg_home/.claude/settings.json"
set +e
neg_out="$(HOME="$neg_home" "$VERIFIER" 2>&1)"
neg_rc=$?
set -e
if [[ $neg_rc -eq 0 && "$neg_out" == *"NEEDS_USER_ACTION"* ]]; then
  echo "NEGATIVE ok: $neg_out"
else
  echo "NEGATIVE FAIL (rc=$neg_rc): $neg_out"
  fail=1
fi
rm -rf "$neg_home"

if [[ $fail -ne 0 ]]; then
  echo FAIL
  exit 1
fi

echo "ALL PASS"
exit 0
