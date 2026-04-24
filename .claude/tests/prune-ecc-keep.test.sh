#!/usr/bin/env bash
# Test: assert that continuous-learning-v2 is in the KEEP_SKILLS array
# of .claude/scripts/prune-ecc.sh so that ECC pruning leaves it enabled.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRUNE_SCRIPT="$SCRIPT_DIR/../scripts/prune-ecc.sh"

if [ ! -f "$PRUNE_SCRIPT" ]; then
  echo "FAIL: $PRUNE_SCRIPT not found" >&2
  exit 1
fi

count="$(grep -c 'continuous-learning-v2' "$PRUNE_SCRIPT" || true)"

if [ "${count:-0}" -ge 1 ]; then
  echo "PASS"
  exit 0
else
  echo "FAIL: continuous-learning-v2 not in KEEP_SKILLS" >&2
  exit 1
fi
