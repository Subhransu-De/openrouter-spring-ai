#!/usr/bin/env bash
set -euo pipefail

report=$(find garage-output -type f -name capability-report.md -print -quit 2>/dev/null || true)
if [[ -n "$report" ]]; then
  cat "$report" >> "$GITHUB_STEP_SUMMARY"
fi
