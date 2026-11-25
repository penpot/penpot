#!/usr/bin/env bash
set -euo pipefail

context="$RUNNER_TEMP/review"
: "${CLAUDE_CODE_OAUTH_TOKEN:?Set the Claude Code OAuth secret}"
effort_args=()
[[ $EFFORT == default ]] || effort_args=(--effort "$EFFORT")
allowed='Read,Glob,Grep,mcp__serena'
[[ $CHECKOUT_SHA != "$HEAD_SHA" ]] || allowed+=',Bash'
npx --yes --package=@anthropic-ai/claude-code@2.1.323 -- claude --print \
  --model "$MODEL" "${effort_args[@]}" --strict-mcp-config --mcp-config "$context/mcp.json" \
  --add-dir "$context" --allowedTools "$allowed" --setting-sources '' \
  < "$context/prompt" > "$context/answer"
