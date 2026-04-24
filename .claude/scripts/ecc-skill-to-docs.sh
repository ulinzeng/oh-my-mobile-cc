#!/usr/bin/env bash
# ecc-skill-to-docs.sh
# Convert an ECC evolved skill (~/.claude/homunculus/.../evolved/skills/*.md)
# into a project-schema skill stub under docs/skills/drafts/.
# Maps front-matter keys, preserves related-specs, injects provenance
# (evolved-from / evolved-confidence / draft: true), and redacts common
# secret patterns from the body.
set -euo pipefail
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8

SOURCE="${1:?usage: ecc-skill-to-docs.sh <source.md> [out-dir]}"
OUT_DIR="${2:-docs/skills/drafts}"

[[ -f "${SOURCE}" ]] || { echo "ERROR: source not found: ${SOURCE}" >&2; exit 1; }
mkdir -p "${OUT_DIR}"

# Split front-matter and body. Assumes file begins with `---`.
FM="$(awk 'BEGIN{c=0} /^---[[:space:]]*$/{c++; if(c==2) exit; next} c==1{print}' "${SOURCE}")"
BODY="$(awk 'BEGIN{c=0} /^---[[:space:]]*$/{c++; next} c>=2{print}' "${SOURCE}")"

# Extract a scalar top-level key from the front-matter (strips quotes).
get_scalar() {
  local key="$1"
  awk -v k="${key}" '
    $0 ~ "^" k ":" {
      sub("^" k ":[[:space:]]*", "")
      gsub(/^["\x27]|["\x27]$/, "")
      print
      exit
    }' <<<"${FM}"
}

# Extract a list field (trigger or related-specs). Supports:
#   key: "scalar"
#   key: [a, b, c]   (flow)
#   key:             (block form with leading `- item` lines)
# Emits one item per line. Strips quotes + whitespace.
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
    }' <<<"${FM}"
}

# Render one-item-per-line list as a YAML flow array: [a, b, c].
render_flow_array() {
  awk '
    BEGIN { first = 1; printf "[" }
    NF    { if (!first) printf ", "; printf "%s", $0; first = 0 }
    END   { print "]" }
  '
}

# normalize_triggers: read trigger from FM, emit YAML `triggers: [...]` line.
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

ID="$(get_scalar id)"
DOMAIN="$(get_scalar domain)"
ACTION="$(get_scalar action)"
CONF="$(get_scalar confidence)"

if [[ -z "${ID}" ]]; then
  echo "ERROR: source missing 'id:' field" >&2
  exit 1
fi

DESTFILE="${OUT_DIR}/${ID}.md"
if [[ -e "${DESTFILE}" ]]; then
  echo "SKIP: ${DESTFILE} already exists"
  exit 0
fi

DESCRIPTION="[${DOMAIN}] ${ACTION}"
TRIGGERS_LINE="$(normalize_triggers)"

RELATED_ITEMS="$(extract_list related-specs)"
RELATED_LINE=""
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
