#!/usr/bin/env bash
# ecc-skill-to-docs.sh
# Convert an ECC evolved skill (~/.claude/homunculus/.../evolved/skills/*.md)
# into a project-schema skill stub under docs/skills/drafts/.
# Maps front-matter keys, preserves related-specs, injects provenance
# (evolved-from / evolved-confidence / draft: true), and redacts common
# secret patterns from the body.
set -euo pipefail
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

# ---- pure helpers (do not depend on globals; safe to source) ----------------

# Split: print front-matter between the first two `---` markers.
split_frontmatter() {
  awk 'BEGIN{c=0} /^---[[:space:]]*$/{c++; if(c==2) exit; next} c==1{print}' "$1"
}

# Split: print body after the second `---` marker.
split_body() {
  awk 'BEGIN{c=0} /^---[[:space:]]*$/{c++; next} c>=2{print}' "$1"
}

# Extract a scalar top-level key from a front-matter string on stdin.
get_scalar() {
  local key="$1"
  awk -v k="${key}" '
    $0 ~ "^" k ":" {
      sub("^" k ":[[:space:]]*", "")
      gsub(/^["\x27]|["\x27]$/, "")
      print
      exit
    }'
}

# Extract a list field (trigger / related-specs) from a front-matter string
# on stdin. Supports scalar, flow array, and block array forms. Emits one
# item per line with surrounding quotes/whitespace stripped.
extract_list() {
  local key="$1"
  awk -v k="${key}" '
    BEGIN { in_block = 0 }
    {
      if ($0 ~ "^" k ":") {
        line = $0
        sub("^" k ":[[:space:]]*", "", line)
        if (line ~ /^\[/) {
          sub(/^\[/, "", line); sub(/\][[:space:]]*$/, "", line)
          n = split(line, arr, ",")
          for (i = 1; i <= n; i++) {
            item = arr[i]
            gsub(/^[[:space:]]+|[[:space:]]+$/, "", item)
            gsub(/^["\x27]|["\x27]$/, "", item)
            if (item != "") print item
          }
          in_block = 0
          next
        } else if (line == "") {
          in_block = 1
          next
        } else {
          gsub(/^["\x27]|["\x27]$/, "", line)
          print line
          in_block = 0
          next
        }
      }
      if (in_block == 1) {
        if ($0 ~ /^-[[:space:]]+/) {
          item = $0
          sub(/^-[[:space:]]+/, "", item)
          gsub(/^["\x27]|["\x27]$/, "", item)
          print item
          next
        }
        # Any non-list, non-blank line ends the block.
        if ($0 !~ /^[[:space:]]*$/) in_block = 0
      }
    }'
}

# Render one-item-per-line list as a YAML flow array: [a, b, c].
render_flow_array() {
  awk '
    BEGIN { first = 1; printf "[" }
    NF    { if (!first) printf ", "; printf "%s", $0; first = 0 }
    END   { print "]" }
  '
}

# normalize_triggers: given a front-matter string on stdin, emit a
# `triggers: [...]` YAML line (flow form).
normalize_triggers() {
  local items; items="$(extract_list trigger)"
  if [[ -z "${items}" ]]; then
    printf 'triggers: []\n'
  else
    printf 'triggers: %s\n' "$(printf '%s\n' "${items}" | render_flow_array)"
  fi
}

# redact_secrets: sanitize BODY text (not front-matter).
redact_secrets() {
  sed \
    -e 's#/Users/[A-Za-z0-9._/-]*#<REDACTED-PATH>#g' \
    -e 's#/home/[A-Za-z0-9._/-]*#<REDACTED-PATH>#g' \
  | sed -E 's/AKIA[0-9A-Z]{16}/<REDACTED-KEY>/g' \
  | sed -E 's/sk-[A-Za-z0-9]{3,}/<REDACTED-KEY>/g' \
  | sed -E 's/(API_KEY|SECRET|TOKEN)=[A-Za-z0-9_.-]+/\1=<REDACTED-KEY>/g'
}

# build_frontmatter: emit the project-schema YAML block on stdout.
build_frontmatter() {
  local id="$1" description="$2" triggers_line="$3" \
        related_line="$4" evolved_from="$5" evolved_conf="$6"
  printf -- '---\n'
  printf 'name: %s\n' "${id}"
  printf 'description: %s\n' "${description}"
  printf '%s\n' "${triggers_line}"
  if [[ -n "${related_line}" ]]; then
    printf '%s\n' "${related_line}"
  fi
  printf 'evolved-from: %s\n' "${evolved_from}"
  if [[ -n "${evolved_conf}" ]]; then
    printf 'evolved-confidence: %s\n' "${evolved_conf}"
  fi
  printf 'draft: true\n'
  printf -- '---\n'
}

# ---- orchestration ----------------------------------------------------------

main() {
  local SOURCE="${1:?usage: ecc-skill-to-docs.sh <source.md> [out-dir]}"
  local OUT_DIR="${2:-docs/skills/drafts}"

  [[ -f "${SOURCE}" ]] || { echo "ERROR: source not found: ${SOURCE}" >&2; exit 1; }
  mkdir -p "${OUT_DIR}"

  local FM BODY
  FM="$(split_frontmatter "${SOURCE}")"
  BODY="$(split_body "${SOURCE}")"

  local ID DOMAIN ACTION CONF
  ID="$(get_scalar id <<<"${FM}")"
  DOMAIN="$(get_scalar domain <<<"${FM}")"
  ACTION="$(get_scalar action <<<"${FM}")"
  CONF="$(get_scalar confidence <<<"${FM}")"

  if [[ -z "${ID}" ]]; then
    echo "ERROR: source missing 'id:' field" >&2
    exit 1
  fi

  local DESTFILE="${OUT_DIR}/${ID}.md"
  if [[ -e "${DESTFILE}" ]]; then
    echo "SKIP: ${DESTFILE} already exists"
    return 0
  fi

  local DESCRIPTION="[${DOMAIN}] ${ACTION}"
  local TRIGGERS_LINE
  TRIGGERS_LINE="$(normalize_triggers <<<"${FM}")"

  local RELATED_ITEMS RELATED_LINE=""
  RELATED_ITEMS="$(extract_list related-specs <<<"${FM}")"
  if [[ -n "${RELATED_ITEMS}" ]]; then
    RELATED_LINE="related-specs: $(printf '%s\n' "${RELATED_ITEMS}" | render_flow_array)"
  fi

  {
    build_frontmatter \
      "${ID}" \
      "${DESCRIPTION}" \
      "${TRIGGERS_LINE}" \
      "${RELATED_LINE}" \
      "${SOURCE}" \
      "${CONF}"
    printf '\n'
    printf '%s\n' "${BODY}" | redact_secrets
  } > "${DESTFILE}"

  echo "OK: wrote ${DESTFILE}"
}

# Only run main when executed directly (not when sourced for testing).
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
