#!/usr/bin/env bash
set -euo pipefail

if [[ -z "${OPENROUTER_API_KEY:-}" ]]; then
  echo "$GARAGE_KEY_NAME is not configured." >&2
  exit 1
fi

jar_path=$(find openrouter-spring-ai-samples/target -maxdepth 1 -type f \
  -name 'openrouter-spring-ai-samples-*.jar' ! -name '*.original' -print -quit)
test -n "$jar_path"

# Preserve literal arguments, including empty option values, without shell evaluation.
args=()
while IFS= read -r argument || [[ -n "$argument" ]]; do
  if [[ -n "$argument" ]]; then
    args+=("$argument")
  fi
done <<< "$GARAGE_ARGUMENTS"
if [[ -n "$GARAGE_IMAGE_QUALITY" ]]; then
  args+=("--image-quality=$GARAGE_IMAGE_QUALITY")
fi
java -jar "$jar_path" "${args[@]}"
