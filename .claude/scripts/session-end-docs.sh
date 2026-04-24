#!/usr/bin/env bash
# SessionEnd hook for oh-my-mobile-cc.
# Produces openspec validation + CODEMAPS diffs + CHANGELOG append at the end of a Claude Code session.
#
# Registered via .claude/settings.local.json -> hooks.SessionEnd.
#
# Environment:
#   PENDING_DOCS_ONLY=1  Only write to .claude/pending-docs/ for human review; do not touch tracked files.
#   SESSION_END_DEBUG=1  Verbose trace.

set -u
[ "${SESSION_END_DEBUG:-0}" = "1" ] && set -x

cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)" || exit 0
ROOT="$(pwd)"
SCRIPTS_DIR="$ROOT/.claude/scripts"
PENDING="$ROOT/.claude/pending-docs"
# Only materialize the pending dir when the mode is explicitly requested.
# (Previously unconditional — created a ghost path even after cleanup.)
if [ "${PENDING_DOCS_ONLY:-0}" = "1" ]; then
  mkdir -p "$PENDING"
fi

TS="$(date +%Y-%m-%dT%H:%M:%S%z)"
log() { echo "[session-end-docs $TS] $*"; }

step_openspec_validate() {
  if ! command -v openspec >/dev/null 2>&1; then
    log "openspec CLI not on PATH — skipping validate"
    return 0
  fi
  log "openspec validate --strict"
  openspec validate --strict || log "openspec validate reported issues (non-blocking)"
}

step_list_archive_candidates() {
  if ! command -v openspec >/dev/null 2>&1; then return 0; fi
  log "checking for changes ready to archive"
  # openspec list prints active changes; a change whose tasks.md has all [x] is archive-ready.
  # We don't parse JSON here; just print human hint.
  local changes_dir="$ROOT/openspec/changes"
  [ -d "$changes_dir" ] || return 0
  for tasks in "$changes_dir"/*/tasks.md; do
    [ -f "$tasks" ] || continue
    local id; id="$(basename "$(dirname "$tasks")")"
    if ! grep -q "^- \[ \]" "$tasks" 2>/dev/null; then
      log "  ready to archive: $id  (all tasks checked)"
    fi
  done
}

step_gen_codemaps() {
  local gen="$SCRIPTS_DIR/gen-codemaps.sh"
  if [ ! -x "$gen" ]; then
    log "gen-codemaps.sh missing or not executable — skipping"
    return 0
  fi
  log "generating CODEMAPS"
  if [ "${PENDING_DOCS_ONLY:-0}" = "1" ]; then
    OUT_DIR="$PENDING/CODEMAPS" "$gen" || log "gen-codemaps failed (non-blocking)"
  else
    OUT_DIR="$ROOT/docs/CODEMAPS" "$gen" || log "gen-codemaps failed (non-blocking)"
  fi
}

step_append_changelog() {
  local changelog="$ROOT/CHANGELOG.md"
  # Collect conventional-commit subjects since last tag (or last 20 if none).
  local range_start
  range_start="$(git describe --tags --abbrev=0 2>/dev/null || echo "")"
  local range_arg=""
  [ -n "$range_start" ] && range_arg="$range_start..HEAD"
  local entries
  entries="$(git log --pretty=format:'%s' $range_arg 2>/dev/null | grep -E '^(feat|fix|chore|docs|refactor|test|build|ci)(\(|:)' || true)"
  [ -z "$entries" ] && { log "no new conventional commits to append"; return 0; }
  local target
  if [ "${PENDING_DOCS_ONLY:-0}" = "1" ]; then
    target="$PENDING/CHANGELOG.fragment.md"
  else
    target="$changelog"
    [ -f "$target" ] || printf "# Changelog\n\n" > "$target"
  fi
  {
    printf "\n## %s\n\n" "$TS"
    printf "%s\n" "$entries" | sed 's/^/- /'
  } >> "$target"
  log "appended $(printf '%s\n' "$entries" | wc -l | tr -d ' ') entry lines -> $target"
}

step_archive_index() {
  local gen="$SCRIPTS_DIR/gen-archive-index.sh"
  if [ ! -x "$gen" ]; then
    log "gen-archive-index.sh missing or not executable — skipping"
    return 0
  fi
  log "regenerating openspec archive INDEX"
  "$gen" || log "gen-archive-index failed (non-blocking)"
}

step_failure_cluster_hint() {
  local chk="$SCRIPTS_DIR/session-failure-cluster-check.sh"
  if [ ! -x "$chk" ]; then return 0; fi
  "$chk" || true
}

log "start"
step_openspec_validate
step_list_archive_candidates
step_gen_codemaps
step_append_changelog
step_archive_index
step_failure_cluster_hint
log "done"
exit 0
