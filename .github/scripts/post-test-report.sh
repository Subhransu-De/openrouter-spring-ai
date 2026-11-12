#!/usr/bin/env bash
# Post the two test report comments (counts.md and timing.md) on a pull request.
#
# A new commit gets two new comments, and the reports of earlier commits are collapsed as outdated.
# A re-run for the same commit edits that commit's comments instead of posting them again.
# Usage: post-test-report.sh <pull-request-number> <head-commit-sha> <comments-dir>
set -euo pipefail

pr="$1"
sha="$2"
dir="$3"
[[ "$pr" =~ ^[0-9]+$ ]] || { echo "invalid pull request number: $pr" >&2; exit 1; }
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { echo "invalid commit: $sha" >&2; exit 1; }
repo="${GITHUB_REPOSITORY:?}"

# id, node id and first line (the hidden marker) of every earlier report comment from this workflow.
existing=$(gh api --paginate "repos/$repo/issues/$pr/comments" \
  --jq '.[] | select(.user.login == "github-actions[bot]") | select(.body | startswith("<!-- test-report:"))
        | [.id, .node_id, (.body | split("\n") | .[0])] | @tsv')

for kind in counts timing; do
  marker="<!-- test-report:$kind:$sha -->"
  id=$(printf '%s\n' "$existing" | awk -F '\t' -v m="$marker" '$3 == m { print $1; exit }')
  if [[ -n "$id" ]]; then
    gh api --method PATCH "repos/$repo/issues/comments/$id" -F "body=@$dir/$kind.md" > /dev/null
  else
    gh api --method POST "repos/$repo/issues/$pr/comments" -F "body=@$dir/$kind.md" > /dev/null
  fi
done

# Collapse the reports of earlier commits so the newest pair is the one to read.
printf '%s\n' "$existing" | awk -F '\t' -v s=":$sha -->" 'NF == 3 && index($3, s) == 0 { print $2 }' |
  while read -r node; do
    # shellcheck disable=SC2016 # $id is a GraphQL variable, not a shell one.
    gh api graphql -f id="$node" \
      -f query='mutation($id: ID!) { minimizeComment(input: {subjectId: $id, classifier: OUTDATED}) { clientMutationId } }' \
      > /dev/null || echo "could not collapse comment $node" >&2
  done
