#!/usr/bin/env bash
set -euo pipefail

context="$RUNNER_TEMP/review"
repo="repos/$GITHUB_REPOSITORY/pulls/$PR"
[[ $(gh api "$repo" --jq .head.sha) == "$HEAD_SHA" ]] || { echo "PR head changed. Request a new review."; exit 1; }
# Some CLIs wrap a JSON answer in a Markdown code fence.
sed '/^\s*```\(json\)\?\s*$/d' "$context/answer" |
  jq -es --arg attribution "**$PROVIDER · $MODEL · $EFFORT**" --arg sha "$HEAD_SHA" '
    if length != 1 then error("expected one answer") else .[0] end |
    select((.summary | type == "string" and length > 0) and (.comments | type == "array")) |
    {commit_id:$sha,event:"COMMENT",body:($attribution + "\n\n" + .summary),
      comments:[.comments[] | {path,line,side,body}]}' > "$context/review.json"
if [[ $GITHUB_EVENT_NAME == pull_request_review_comment ]]; then
  root=$(jq -r '.comment.in_reply_to_id // .comment.id' "$GITHUB_EVENT_PATH")
  jq '{body:(.body + ([.comments[] | "\n\n**\(.path):\(.line)** — \(.body)"] | join("")))}' "$context/review.json" |
    gh api "$repo/comments/$root/replies" --input - --jq .html_url
else
  gh api "$repo/reviews" --input "$context/review.json" --jq .html_url
fi
