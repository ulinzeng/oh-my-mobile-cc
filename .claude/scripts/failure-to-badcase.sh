#!/usr/bin/env bash
# Auto-write a bad-case stub when a stuck TDD loop is detected
# (>= 3 [red] commits with 0 [green] commits in the session window).
#
# Idempotent: if a file with the same cluster hash already exists under
# $BADCASES_DIR (default docs/badcases), the script SKIPs and exits 0.
#
# Environment:
#   FCLUSTER_FAKE_LOG  — injected commit-subject log (one per line); used by tests.
#   BADCASES_DIR       — target directory (default: docs/badcases).
#
# Called by: .claude/scripts/session-failure-cluster-check.sh
# Silent on negative signal; exits 0 unconditionally (advisory only).

set -euo pipefail

cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)" || exit 0

if [ -n "${FCLUSTER_FAKE_LOG:-}" ]; then
  log="$FCLUSTER_FAKE_LOG"
else
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

if [ "${red_count:-0}" -lt 3 ] || [ "${green_count:-0}" -ne 0 ]; then
  exit 0
fi

# Extract up to 3 [red] subjects, sorted for stable hash.
reds="$(printf '%s\n' "$log" | grep '\[red\]' | head -n 3)"
sorted_reds="$(printf '%s\n' "$reds" | sort)"
hash7="$(printf '%s' "$sorted_reds" | shasum | cut -c1-7)"

target_dir="${BADCASES_DIR:-docs/badcases}"
mkdir -p "$target_dir"

today="$(date +%Y-%m-%d)"
target="$target_dir/${today}-auto-${hash7}.md"

if [ -f "$target" ]; then
  echo "SKIP: $target exists"
  exit 0
fi

{
  printf -- '---\n'
  printf 'status: auto-generated\n'
  printf 'signal-source: [red-cluster]\n'
  printf 'date: %s\n' "$today"
  printf 'hash: %s\n' "$hash7"
  printf -- '---\n\n'
  printf '# Auto-generated bad case: TDD stall cluster\n\n'
  printf 'Detected %s `[red]` commits with 0 `[green]` commits.\n\n' "$red_count"
  printf '## Offending commits\n\n'
  printf '%s\n' "$reds" | sed 's/^/- /'
  printf '\n'
  printf 'Investigate the common cause and, if confirmed, promote this to\n'
  printf 'a curated bad case with a proper `tag:` and `lesson:` field.\n'
} > "$target"

echo "wrote $target"
exit 0
