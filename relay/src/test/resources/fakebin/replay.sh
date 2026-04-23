#!/usr/bin/env bash
# Minimal replay binary for ClaudeProcessTest.
# Emits the file passed as $1 to stdout verbatim, then exits 0.
# If a second arg is given, also writes that fixed string to stderr so
# tests can assert stderr is separated from the event stream.
set -euo pipefail
cat "$1"
if [[ "${2:-}" != "" ]]; then
  echo "$2" >&2
fi
