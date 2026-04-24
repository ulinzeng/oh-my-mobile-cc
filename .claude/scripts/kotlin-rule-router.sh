#!/usr/bin/env bash
# PreToolUse router: when the agent is about to Edit/Write a file, print a
# suggestion about which Kotlin rule files are relevant for that path.
# Agents can then Read only the suggested rules instead of bulk-loading.
#
# This is NOT a gate — it always exits 0. The hint appears in Claude Code's
# PostToolUse context for the implementing agent.
#
# Spec: openspec/changes/add-doc-lifecycle-infra/specs/docs-lifecycle/spec.md
#       Requirement "Scoped rule loading".
#
# The tool input arrives on stdin as JSON from Claude Code. We extract the
# file_path field using a small awk/grep fallback (we deliberately avoid jq
# because not every dev env has it).

set -u

# Read stdin; bail out silently on any parse issue.
payload="$(cat 2>/dev/null || true)"
[ -n "$payload" ] || exit 0

# Extract file_path. Claude Code hook payload shape:
#   { "tool_input": { "file_path": "...", ... }, ... }
file_path="$(
  printf '%s' "$payload" \
    | awk -v RS='"file_path"' 'NR==2 { print }' \
    | awk -F'"' '{ print $2 }' \
    | head -n 1
)"

[ -n "$file_path" ] || exit 0

# Only fire for Kotlin/Gradle files.
case "$file_path" in
  *.kt|*.kts) ;;
  *) exit 0 ;;
esac

# Route file path → rule files.
rules=()
case "$file_path" in
  *Test/*.kt|*test/*.kt|*/test/*/*.kt|*/Test/*/*.kt)
    rules=("coding-style.md" "patterns.md" "testing.md")
    ;;
  */relay/*|*/relay/*.kt)
    rules=("coding-style.md" "patterns.md" "security.md")
    ;;
  *.gradle.kts|*build.gradle.kts|*settings.gradle.kts)
    rules=("hooks.md" "patterns.md")
    ;;
  *.kt)
    rules=("coding-style.md" "patterns.md")
    ;;
esac

[ "${#rules[@]}" -gt 0 ] || exit 0

# Emit a hint as additionalContext via stdout (Claude Code treats this as a
# system-reminder for the next tool call).
{
  echo "[kotlin-rule-router] Recommended rules for $file_path:"
  for r in "${rules[@]}"; do
    echo "  - .claude/rules/kotlin/$r"
  done
  echo "Bulk-loading all five is wasteful; read only the listed files."
} >&2

exit 0
