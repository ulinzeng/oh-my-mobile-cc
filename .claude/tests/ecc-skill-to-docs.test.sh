#!/usr/bin/env bash
# Test runner for .claude/scripts/ecc-skill-to-docs.sh
# Expects converter to emit project-schema front-matter with redacted body.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONVERTER="${REPO_ROOT}/scripts/ecc-skill-to-docs.sh"
FIXTURES="${SCRIPT_DIR}/fixtures/ecc-skill"
TMP_ROOT="$(mktemp -d -t ecc-skill-test.XXXXXX)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() {
  echo "FAIL ($1): $2" >&2
  echo "--- output ---" >&2
  cat "$3" >&2 || true
  echo "--- end ---" >&2
  exit 1
}

run_case() {
  local fixture="$1"
  local out_dir="${TMP_ROOT}/$(basename "${fixture}" .md)"
  mkdir -p "${out_dir}"
  bash "${CONVERTER}" "${fixture}" "${out_dir}" >/dev/null
  local produced
  produced="$(find "${out_dir}" -maxdepth 1 -type f -name '*.md' | head -n 1)"
  if [[ -z "${produced}" ]]; then
    echo "FAIL ($(basename "${fixture}")): no output file" >&2
    exit 1
  fi
  echo "${produced}"
}

assert_contains() {
  local file="$1" needle="$2" label="$3"
  if ! grep -qF -- "${needle}" "${file}"; then
    fail "${label}" "missing: ${needle}" "${file}"
  fi
}

assert_not_contains() {
  local file="$1" needle="$2" label="$3"
  if grep -qF -- "${needle}" "${file}"; then
    fail "${label}" "unexpected leak: ${needle}" "${file}"
  fi
}

# --- Case 01: minimal ---
f01="${FIXTURES}/01-minimal.md"
out01="$(run_case "${f01}")"
assert_contains "${out01}" "name: validate-input-first" "01"
# Accept flow [x] or block form
if ! grep -qE '^triggers:\s*\[[^]]*user input handling[^]]*\]' "${out01}" \
   && ! grep -qE '^- user input handling' "${out01}"; then
  fail "01" "triggers not found in either flow or block form" "${out01}"
fi
assert_contains "${out01}" "draft: true" "01"
assert_contains "${out01}" "evolved-from:" "01"
assert_contains "${out01}" "01-minimal.md" "01"
echo "PASS (01-minimal.md)"

# --- Case 02: array trigger ---
f02="${FIXTURES}/02-array-trigger.md"
out02="$(run_case "${f02}")"
assert_contains "${out02}" "name: stream-json-pairing" "02"
# Must keep all 3 triggers (flow or block acceptable)
for t in "claude -p" "CCEvent" "stream-json"; do
  if ! grep -qF -- "${t}" "${out02}"; then
    fail "02" "missing trigger token: ${t}" "${out02}"
  fi
done
echo "PASS (02-array-trigger.md)"

# --- Case 03: related-specs preserved ---
f03="${FIXTURES}/03-with-specs.md"
out03="$(run_case "${f03}")"
assert_contains "${out03}" "name: ed25519-consistency" "03"
if ! grep -qE 'related-specs:.*specs/pairing/spec.md' "${out03}" \
   && ! grep -qE '^- specs/pairing/spec.md' "${out03}"; then
  fail "03" "related-specs not preserved" "${out03}"
fi
echo "PASS (03-with-specs.md)"

# --- Case 04: redaction in body ---
f04="${FIXTURES}/04-abspath-leak.md"
out04="$(run_case "${f04}")"
assert_not_contains "${out04}" "/Users/alice" "04"
assert_not_contains "${out04}" "/home/bob" "04"
assert_not_contains "${out04}" "AKIAIOSFODNN7EXAMPLE" "04"
assert_not_contains "${out04}" "sk-abc123" "04"
echo "PASS (04-abspath-leak.md)"

# --- Idempotency: running again on the same destination should SKIP ---
out_dir_idem="${TMP_ROOT}/idem"
mkdir -p "${out_dir_idem}"
bash "${CONVERTER}" "${f01}" "${out_dir_idem}" >/dev/null
second="$(bash "${CONVERTER}" "${f01}" "${out_dir_idem}")"
if ! grep -qE '^SKIP:' <<<"${second}"; then
  echo "FAIL (idempotency): expected SKIP on second run, got: ${second}" >&2
  exit 1
fi
echo "PASS (idempotent)"
