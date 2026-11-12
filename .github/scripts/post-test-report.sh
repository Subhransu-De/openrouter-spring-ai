#!/usr/bin/env bash
# Post the two test report comments (counts.md and timing.md) on a pull request.
#
# The chart images (*.png) go to the test-report-charts branch first, under pr-<number>/<commit>/, because
# GitHub has no API to attach an image to a comment. If that push fails, the comments drop their images
# and show the numbers instead.
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
branch=test-report-charts

push_charts() {
  local work attempt
  work=$(mktemp -d)
  gh auth setup-git
  git -C "$work" init -q
  git -C "$work" config user.name "github-actions[bot]"
  git -C "$work" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
  git -C "$work" remote add origin "https://github.com/$repo.git"
  for attempt in 1 2 3; do
    if git -C "$work" fetch -q --depth=1 origin "$branch"; then
      git -C "$work" checkout -q -B "$branch" FETCH_HEAD
    else
      git -C "$work" checkout -q --orphan "$branch"
    fi
    mkdir -p "$work/pr-$pr/$sha"
    cp "$dir"/*.png "$work/pr-$pr/$sha/"
    git -C "$work" add -A
    git -C "$work" diff --cached --quiet && return 0
    git -C "$work" commit -q -m "Test report charts for #$pr at ${sha:0:7}"
    # Another pull request may have pushed in between; fetch again and retry.
    git -C "$work" push -q origin "$branch" && return 0
    echo "push attempt $attempt failed" >&2
    git -C "$work" reset -q --hard
  done
  return 1
}

if compgen -G "$dir/*.png" > /dev/null && ! push_charts; then
  echo "could not push the charts; posting the numbers without images" >&2
  sed -i -e '/^!\[.*\](https:\/\/raw\.githubusercontent\.com\//d' -e 's/^<details>$/<details open>/' \
    "$dir/counts.md" "$dir/timing.md"
fi

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
