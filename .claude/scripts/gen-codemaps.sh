#!/usr/bin/env bash
# Generate per-module CODEMAPS.
# Drives a simple public-symbol dump per Kotlin module. At W0 this is a stub that
# emits per-module file listings; real AST dumps come in W2 (ktlint/Dokka or a custom grep).
#
# Output dir: $OUT_DIR (defaults to docs/CODEMAPS).

set -u
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
OUT_DIR="${OUT_DIR:-$ROOT/docs/CODEMAPS}"
mkdir -p "$OUT_DIR"

TS="$(date +%Y-%m-%dT%H:%M:%S%z)"

emit_module() {
  local module="$1"
  local src_root="$2"
  local out="$OUT_DIR/${module}.md"
  {
    printf "# CODEMAP: %s\n\n" "$module"
    printf "_Generated %s by .claude/scripts/gen-codemaps.sh_\n\n" "$TS"
    printf "Source root: \`%s\`\n\n" "$src_root"
    printf "## Kotlin files\n\n"
    if [ -d "$ROOT/$src_root" ]; then
      (cd "$ROOT" && find "$src_root" -type f -name '*.kt' | sort) | sed 's/^/- /'
    else
      printf "_(module not yet present)_\n"
    fi
    printf "\n## Public top-level declarations (best-effort)\n\n"
    if [ -d "$ROOT/$src_root" ]; then
      (cd "$ROOT" && grep -rE '^(public |internal )?(class|object|interface|fun|val|var) ' "$src_root" \
        --include='*.kt' 2>/dev/null | sort) | sed 's/^/    /'
    fi
  } > "$out"
  echo "[gen-codemaps] wrote $out"
}

emit_module shared  shared/src
emit_module relay   relay/src
emit_module androidApp androidApp/src
