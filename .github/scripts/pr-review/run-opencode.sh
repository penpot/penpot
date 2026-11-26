#!/usr/bin/env bash
set -euo pipefail

context="$RUNNER_TEMP/review"
: "${OPENCODE_GO_API_KEY:?Set the OPENCODE_GO_API_KEY Actions secret}"
variant=()
[[ $EFFORT == default ]] || variant=(--variant "$EFFORT")
opencode run --pure --model "$MODEL" "${variant[@]}" --format json < "$context/prompt" |
  jq -ers '[.[] | select(.type == "text") | .part.text] | last // error("OpenCode returned no text")' > "$context/answer"
