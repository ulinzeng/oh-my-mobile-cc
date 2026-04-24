#!/usr/bin/env bash
# Test: failure-to-badcase.sh writes an auto-badcase on [red]-cluster and is
# idempotent on re-run; no file written on negative fixture.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
script="$repo_root/.claude/scripts/failure-to-badcase.sh"
fixtures="$repo_root/.claude/tests/fixtures/gitlog"

tmp_root="$(mktemp -d)"
DOCS_BADCASES_DIR="$tmp_root/badcases"
mkdir -p "$DOCS_BADCASES_DIR"

cleanup() { rm -rf "$tmp_root"; }
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  echo "--- dir listing ---" >&2
  ls -la "$DOCS_BADCASES_DIR" >&2 || true
  exit 1
}

# --- POSITIVE: 3 [red], 0 [green] ---
pos_log="$(cat "$fixtures/pos-3red-0green.txt")"
FCLUSTER_FAKE_LOG="$pos_log" BADCASES_DIR="$DOCS_BADCASES_DIR" bash "$script" \
  || fail "positive run exited non-zero"

today="$(date +%Y-%m-%d)"
# shellcheck disable=SC2012
count_pos="$(ls "$DOCS_BADCASES_DIR" | grep -c "^${today}-auto-.*\.md$" || true)"
[ "$count_pos" = "1" ] || fail "expected 1 auto-file after positive run, got $count_pos"

written="$(ls "$DOCS_BADCASES_DIR"/${today}-auto-*.md 2>/dev/null | head -n1)"
[ -n "$written" ] || fail "no auto-badcase file written"

grep -q '^status: auto-generated' "$written" \
  || fail "missing 'status: auto-generated' in $written"
grep -q '^signal-source: \[red-cluster\]' "$written" \
  || fail "missing 'signal-source: [red-cluster]' in $written"
grep -q 'case a' "$written" || fail "missing 'case a' subject in $written"
grep -q 'case b' "$written" || fail "missing 'case b' subject in $written"
grep -q 'case c' "$written" || fail "missing 'case c' subject in $written"

# --- IDEMPOTENCE: same fixture second run ---
FCLUSTER_FAKE_LOG="$pos_log" BADCASES_DIR="$DOCS_BADCASES_DIR" bash "$script" \
  || fail "idempotent run exited non-zero"
# shellcheck disable=SC2012
count_idem="$(ls "$DOCS_BADCASES_DIR" | grep -c "^${today}-auto-.*\.md$" || true)"
[ "$count_idem" = "1" ] || fail "expected 1 auto-file after idempotent run, got $count_idem"

# --- NEGATIVE: 1 [red], 1 [green] ---
neg_root="$(mktemp -d)"
NEG_DIR="$neg_root/badcases"
mkdir -p "$NEG_DIR"
neg_log="$(cat "$fixtures/neg-1red-1green.txt")"
FCLUSTER_FAKE_LOG="$neg_log" BADCASES_DIR="$NEG_DIR" bash "$script" \
  || { rm -rf "$neg_root"; fail "negative run exited non-zero"; }
# shellcheck disable=SC2012
count_neg="$(ls "$NEG_DIR" | wc -l | tr -d ' ')"
if [ "$count_neg" != "0" ]; then
  rm -rf "$neg_root"
  fail "expected 0 files after negative run, got $count_neg"
fi
rm -rf "$neg_root"

echo "PASS"
