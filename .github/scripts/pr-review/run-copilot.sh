#!/usr/bin/env bash
set -euo pipefail

context="$RUNNER_TEMP/review"
: "${COPILOT_GITHUB_TOKEN:?Set the Copilot GitHub token secret}"
effort_args=()
[[ $EFFORT == default ]] || effort_args=(--reasoning-effort "$EFFORT")
allow=(--allow-tool read --allow-tool serena)
[[ $CHECKOUT_SHA != "$HEAD_SHA" ]] || allow+=(--allow-tool shell)
npx --yes --package=@github/copilot@1.0.110 -- copilot -s -p "$(cat "$context/prompt")" \
  --model "$MODEL" "${effort_args[@]}" --no-auto-update --no-ask-user --disable-builtin-mcps \
  --add-dir "$context" --additional-mcp-config "@$context/mcp.json" "${allow[@]}" > "$context/answer"
