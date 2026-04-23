#!/usr/bin/env bash
# Re-apply ECC skill/agent pruning after an upgrade pulls fresh cache.
#
# Why: ECC plugin is auto-discovered from ./skills/ and ./agents/ under the
# plugin cache dir. We prune by moving unwanted items to {skills,agents}-disabled/.
# When ECC auto-updates (e.g. 1.10.0 -> 1.11.0), cache is re-hydrated fresh and
# our disabled moves are gone. Re-run this script against the new version dir.
#
# Usage:
#   ECC_VERSION=1.11.0 ./prune-ecc.sh
# or let it auto-pick the highest numeric dir:
#   ./prune-ecc.sh

set -euo pipefail

ECC_ROOT="${ECC_ROOT:-$HOME/.claude-internal/plugins/cache/everything-claude-code/everything-claude-code}"

if [ -n "${ECC_VERSION:-}" ]; then
  ECC="$ECC_ROOT/$ECC_VERSION"
else
  ECC="$(ls -d "$ECC_ROOT"/*/ 2>/dev/null | sort -V | tail -n 1)"
  ECC="${ECC%/}"
fi

if [ ! -d "$ECC/skills" ]; then
  echo "FATAL: $ECC/skills not found — wrong ECC_VERSION or ECC not installed?" >&2
  exit 1
fi

echo "Pruning ECC at: $ECC"

mkdir -p "$ECC/skills-disabled" "$ECC/agents-disabled"

# --- keep list ---
KEEP_SKILLS=(
  accessibility agent-sort android-clean-architecture api-design
  architecture-decision-records autonomous-loops backend-patterns
  blueprint code-tour codebase-onboarding coding-standards
  compose-multiplatform-patterns configure-ecc context-budget
  deep-research documentation-lookup e2e-testing exa-search
  git-workflow hexagonal-architecture hookify-rules
  kotlin-coroutines-flows kotlin-ktor-patterns kotlin-patterns
  kotlin-testing mcp-server-patterns repo-scan security-review
  strategic-compact swift-concurrency-6-2 swiftui-patterns
  tdd-workflow team-builder verification-loop
)
KEEP_AGENTS=(
  code-reviewer doc-updater docs-lookup harness-optimizer
  kotlin-build-resolver kotlin-reviewer planner
  refactor-cleaner security-reviewer silent-failure-hunter
)

KEEP_SKILLS_FILE="$(mktemp)"
KEEP_AGENTS_FILE="$(mktemp)"
trap 'rm -f "$KEEP_SKILLS_FILE" "$KEEP_AGENTS_FILE"' EXIT

printf "%s\n" "${KEEP_SKILLS[@]}" | sort -u > "$KEEP_SKILLS_FILE"
printf "%s\n" "${KEEP_AGENTS[@]}" | sort -u > "$KEEP_AGENTS_FILE"

# --- prune skills ---
cd "$ECC/skills"
skill_moved=0
for dir in */; do
  name="${dir%/}"
  if ! grep -qx "$name" "$KEEP_SKILLS_FILE"; then
    mv "$dir" "$ECC/skills-disabled/"
    skill_moved=$((skill_moved + 1))
  fi
done
skill_kept=$(ls "$ECC/skills/" | wc -l | tr -d ' ')

# --- prune agents ---
cd "$ECC/agents"
agent_moved=0
for f in *.md; do
  [ -f "$f" ] || continue
  name="${f%.md}"
  if ! grep -qx "$name" "$KEEP_AGENTS_FILE"; then
    mv "$f" "$ECC/agents-disabled/"
    agent_moved=$((agent_moved + 1))
  fi
done
agent_kept=$(ls "$ECC/agents/" 2>/dev/null | grep -c '\.md$' || echo 0)

# --- prune plugin.json agents array ---
PLUGIN_JSON="$ECC/.claude-plugin/plugin.json"
if [ -f "$PLUGIN_JSON" ]; then
  cp "$PLUGIN_JSON" "$PLUGIN_JSON.pre-prune-backup"
  python3 - "$PLUGIN_JSON" "$KEEP_AGENTS_FILE" <<'PY'
import json, sys
path, keep_file = sys.argv[1], sys.argv[2]
with open(keep_file) as f:
    keep = {line.strip() for line in f if line.strip()}
with open(path) as f:
    d = json.load(f)
if "agents" in d:
    d["agents"] = [a for a in d["agents"]
                   if a.rsplit("/",1)[-1].replace(".md","") in keep]
with open(path, "w") as f:
    json.dump(d, f, indent=2)
print(f"plugin.json agents retained: {len(d.get('agents', []))}")
PY
fi

echo
echo "========== ECC prune summary =========="
echo "Skills kept:    $skill_kept (moved $skill_moved to skills-disabled/)"
echo "Agents kept:    $agent_kept (moved $agent_moved to agents-disabled/)"
echo "plugin.json:    backup at $PLUGIN_JSON.pre-prune-backup"
echo "To restore ALL: rm -rf $ECC/skills $ECC/agents && mv $ECC/skills-disabled $ECC/skills && mv $ECC/agents-disabled $ECC/agents && cp $PLUGIN_JSON.pre-prune-backup $PLUGIN_JSON"
echo "======================================="
