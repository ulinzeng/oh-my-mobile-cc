#!/usr/bin/env bash
# Failure-cluster hint: inspect the session's git log for a stuck TDD loop
# (>= 3 [red] commits with zero [green] commits after the earliest red).
#
# Emits a hint to stderr pointing at docs/badcases/ when the condition holds.
# Exits 0 unconditionally — this is advisory, not a gate.
#
# Spec: openspec/changes/add-doc-lifecycle-infra/specs/docs-lifecycle/spec.md
#       Requirement "Failure-cluster hint".

# Intentionally NOT `set -e` — this script runs in SessionEnd and must never
# abort the chain on a transient error (e.g. empty git log). Unset vars and
# pipeline failures are still caught.
set -uo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)" || exit 0

# Test override: FCLUSTER_FAKE_LOG lets callers inject a fake commit-subject log
# (one subject per line) without touching git. Used by the smoke-test at the
# bottom of this file and by acceptance scripts.
if [ -n "${FCLUSTER_FAKE_LOG:-}" ]; then
  log="$FCLUSTER_FAKE_LOG"
  range="<fake-log>"
else
  # Session window: commits on the current branch since it diverged from main
  # (or the last 30 commits if we're already on main).
  if git rev-parse --verify main >/dev/null 2>&1; then
    current_branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
    if [ "$current_branch" = "main" ]; then
      range="HEAD~30..HEAD"
    else
      range="main..HEAD"
    fi
  else
    range="HEAD~30..HEAD"
  fi
  log="$(git log --pretty=format:'%s' "$range" 2>/dev/null || true)"
fi
[ -n "$log" ] || exit 0

red_count="$(printf '%s\n' "$log" | grep -c '\[red\]' || true)"
green_count="$(printf '%s\n' "$log" | grep -c '\[green\]' || true)"

if [ "${red_count:-0}" -ge 3 ] && [ "${green_count:-0}" -eq 0 ]; then
  cat >&2 <<HINT

[failure-cluster hint] Detected $red_count [red] commits with 0 [green] commits
in the current session window ($range). This often signals a stuck TDD loop.

Consider filing a bad case: docs/badcases/$(date +%Y-%m-%d)-<slug>.md
and registering it in docs/badcases/INDEX.md.
HINT
  # Also persist the cluster as an auto-badcase (idempotent; skips if exists).
  "$(dirname "$0")/failure-to-badcase.sh" || true
fi

exit 0
