#!/usr/bin/env bash
set -euo pipefail
: "${OPENCODE_GO_API_KEY:?Set the OPENCODE_GO_API_KEY Actions secret}"
[[ $(gh api "repos/$GITHUB_REPOSITORY/pulls/$PR" --jq .head.sha) == "$HEAD_SHA" ]] || { echo "PR head changed; request another review."; exit 1; }
# The native agent fetches the PR branch even with persist-credentials off.
gh auth setup-git --hostname github.com
cat >> "$RUNNER_TEMP/review/instructions.md" <<'RULES'
The GitHub agent publishes your final answer in the PR conversation. Post actionable
findings on changed lines with gh api repos/OWNER/REPO/pulls/NUMBER/comments, using
the reviewed commit, path, line, side and body. Leave the working tree clean.
RULES
if jq -e --arg cmd "$MENTIONS" '(.comment.body | gsub("^\\s+|\\s+$"; "")) == $cmd' "$GITHUB_EVENT_PATH" > /dev/null; then
  export PROMPT='Review this pull request.'
fi
if [[ $EFFORT != default ]]; then export VARIANT="$EFFORT"; fi
opencode github run --pure
