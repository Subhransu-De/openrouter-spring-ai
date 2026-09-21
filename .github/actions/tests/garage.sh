#!/usr/bin/env bash
set -euo pipefail

actions_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
cd "$test_dir"

# Synthetic fixtures and a Java double: no build, credentials, or network required.
mkdir -p openrouter-spring-ai-samples/target
jar_path=openrouter-spring-ai-samples/target/openrouter-spring-ai-samples-test.jar
touch "$jar_path" "$jar_path.original"
export GARAGE_KEY_NAME=SYNTHETIC_KEY
export GARAGE_IMAGE_QUALITY=''
export GARAGE_ARGUMENTS=$'--text\n--fallback-models=\n\n--literal=$(touch unexpected)\n--with-space=two words'
export OPENROUTER_API_KEY=synthetic-test-value
export JAVA_EXIT_CODE=0
java() {
  printf '%s\n' "$@" > actual-arguments
  return "$JAVA_EXIT_CODE"
}
export -f java

bash "$actions_dir/run-garage/run.sh"
# The substitution is intentionally literal to detect accidental shell evaluation.
# shellcheck disable=SC2016
printf '%s\n' -jar "$jar_path" --text --fallback-models= \
  '--literal=$(touch unexpected)' '--with-space=two words' > expected-arguments
diff -u expected-arguments actual-arguments
test ! -e unexpected

for quality in '' low; do
  export GARAGE_ARGUMENTS=$'--image\n--request-mode=chat\n--image-surface=streaming'
  export GARAGE_IMAGE_QUALITY="$quality"
  bash "$actions_dir/run-garage/run.sh"
  printf '%s\n' -jar "$jar_path" --image --request-mode=chat --image-surface=streaming > expected-arguments
  if [[ -n "$quality" ]]; then
    printf '%s\n' "--image-quality=$quality" >> expected-arguments
  fi
  diff -u expected-arguments actual-arguments
done

export JAVA_EXIT_CODE=23
status=0
bash "$actions_dir/run-garage/run.sh" || status=$?
test "$status" -eq 23
export JAVA_EXIT_CODE=0

rm actual-arguments
unset OPENROUTER_API_KEY
if bash "$actions_dir/run-garage/run.sh" > missing-key.log 2>&1; then
  echo 'Expected missing key to fail' >&2
  exit 1
fi
grep -Fx 'SYNTHETIC_KEY is not configured.' missing-key.log
test ! -e actual-arguments

export OPENROUTER_API_KEY=synthetic-test-value
rm "$jar_path"
if bash "$actions_dir/run-garage/run.sh"; then
  echo 'Expected missing executable JAR to fail' >&2
  exit 1
fi
test ! -e actual-arguments

export GITHUB_STEP_SUMMARY="$test_dir/summary.md"
bash "$actions_dir/garage-evidence/summarize.sh"
test ! -e "$GITHUB_STEP_SUMMARY"
mkdir -p garage-output/synthetic
printf '%s\n' 'Synthetic capability report' > garage-output/synthetic/capability-report.md
printf '%s\n' 'Existing summary' > "$GITHUB_STEP_SUMMARY"
bash "$actions_dir/garage-evidence/summarize.sh"
printf '%s\n' 'Existing summary' 'Synthetic capability report' > expected-summary
diff -u expected-summary "$GITHUB_STEP_SUMMARY"
echo 'Garage action regression checks passed'
