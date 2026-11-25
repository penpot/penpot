#!/usr/bin/env bash
# Run the CI pull request review on this machine and print the answer
# instead of posting it to GitHub.
#
# Usage: review-local.sh <pr> ['/claude[-<model>-<effort>] [request]']
#   review-local.sh 12345
#   review-local.sh 12345 '/claude-opus-high Check the migration.'
#   review-local.sh 12345 '/copilot'            # needs COPILOT_GITHUB_TOKEN
#   review-local.sh 12345 '/review'             # needs OPENCODE_GO_API_KEY
#
# Claude uses CLAUDE_CODE_OAUTH_TOKEN when set, otherwise the local login.
# Serena runs through uvx when it is not on PATH. Set KEEP_WORKTREE=1 to keep
# the reviewed checkout.
set -euo pipefail

scripts=$(cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(git -C "$scripts" rev-parse --show-toplevel)
pr=${1:?Usage: $0 <pr> ['/claude[-<model>-<effort>] [request]']}
request=${2:-/claude}

work=$(mktemp -d -t pr-review-XXXXXX)
export RUNNER_TEMP="$work"
export GITHUB_REPOSITORY=${GITHUB_REPOSITORY:-penpot/penpot}
# A submitted review event selects the same prompt as a comment without adding a reaction on GitHub.
export GITHUB_EVENT_NAME=pull_request_review
export GITHUB_EVENT_PATH="$work/event.json"
export GITHUB_OUTPUT="$work/request.env"
export DEFAULT_CLAUDE_MODEL=${CLAUDE_MODEL:-sonnet-5.5} DEFAULT_CLAUDE_EFFORT=${CLAUDE_EFFORT:-default}
export DEFAULT_COPILOT_MODEL=${COPILOT_MODEL:-claude-sonnet-5.5} DEFAULT_COPILOT_EFFORT=${COPILOT_EFFORT:-default}
export DEFAULT_GO_MODEL=${OPENCODE_MODEL:-deepseek-v4.1-flash} DEFAULT_GO_EFFORT=${OPENCODE_EFFORT:-high}

jq -n --arg body "$request" --arg user "$(gh api user --jq .login)" --argjson pr "$pr" \
  '{review:{body:$body,user:{login:$user}},pull_request:{number:$pr}}' > "$GITHUB_EVENT_PATH"

touch "$GITHUB_OUTPUT"
bash "$scripts/parse-request.sh"
[[ -s $GITHUB_OUTPUT ]] || { echo "Start the request with /claude, /copilot, /oc or /review." >&2; exit 1; }
while IFS='=' read -r key value; do printf -v "$key" '%s' "$value"; done < "$GITHUB_OUTPUT"
[[ $provider != opencode ]] || provider=OpenCode
export PROVIDER=$provider MODEL=$model EFFORT=$effort PR=$pr HEAD_SHA=$head CHECKOUT_SHA=$checkout

project="$work/project"
cleanup() {
  [[ ${KEEP_WORKTREE:-} == 1 ]] || git -C "$repo_root" worktree remove --force "$project" 2>/dev/null || true
}
trap cleanup EXIT
git -C "$repo_root" fetch --quiet "https://github.com/$GITHUB_REPOSITORY.git" "$CHECKOUT_SHA"
git -C "$repo_root" worktree add --quiet --detach "$project" "$CHECKOUT_SHA"
cd "$project"

# Keep the safe.directory entry the CI script adds out of the user's git config.
GIT_CONFIG_GLOBAL="$work/gitconfig" bash "$scripts/prepare-context.sh"

if ! command -v serena > /dev/null; then
  mkdir -p "$work/bin"
  cat > "$work/bin/serena" <<'EOF'
#!/usr/bin/env bash
exec uvx --quiet --from "${SERENA_SPEC:-git+https://github.com/oraios/serena}" serena "$@"
EOF
  chmod +x "$work/bin/serena"
  export PATH="$work/bin:$PATH"
  echo "Preparing Serena with uvx..." >&2
  serena --help > /dev/null
fi
python3 "$scripts/render-mcp-config.py" "${PROVIDER,,}" > "$work/review/mcp.json"
command -v playwright-cli > /dev/null || echo "playwright-cli is not installed; browser checks are unavailable." >&2

echo "Reviewing PR #$PR at $HEAD_SHA with $PROVIDER · $MODEL · $EFFORT (checkout $CHECKOUT_SHA)" >&2
case $PROVIDER in
  claude)
    if [[ -n ${CLAUDE_CODE_OAUTH_TOKEN:-} ]]; then
      bash "$scripts/run-claude.sh"
    else
      bash <(grep -v CLAUDE_CODE_OAUTH_TOKEN "$scripts/run-claude.sh")
    fi ;;
  copilot) bash "$scripts/run-copilot.sh" ;;
  OpenCode)
    : "${OPENCODE_GO_API_KEY:?Set OPENCODE_GO_API_KEY}"
    export OPENCODE_DISABLE_AUTOUPDATE=true OPENCODE_DISABLE_PROJECT_CONFIG=true
    export GITHUB_PATH="$work/github-path" GITHUB_ENV="$work/github-env"
    GIT_CONFIG_GLOBAL="$work/gitconfig" bash "$scripts/setup-opencode.sh"
    while read -r dir; do PATH="$dir:$PATH"; done < "$GITHUB_PATH"
    while read -r line; do export "${line?}"; done < "$GITHUB_ENV"
    # Record the raw event stream, which run-opencode.sh reduces to the last text part.
    events="$work/review/opencode-events.jsonl"
    real=$(command -v opencode)
    mkdir -p "$work/bin"
    cat > "$work/bin/opencode" <<EOF
#!/usr/bin/env bash
set -o pipefail
"$real" "\$@" --print-logs --log-level "${OPENCODE_LOG_LEVEL:-INFO}" 2> "$work/review/opencode-stderr.log" | tee "$events"
EOF
    chmod +x "$work/bin/opencode"
    PATH="$work/bin:$PATH"
    if ! bash "$scripts/run-opencode.sh"; then
      echo "OpenCode errors:" >&2
      jq -c 'select(.type == "error")' "$events" >&2 2>/dev/null || cat "$events" >&2
      grep "level=ERROR" "$work/review/opencode-stderr.log" | grep -v "failed to load plugin" >&2 || true
      exit 1
    fi ;;
esac

# Build the same review payload post-review.sh sends, then print it.
context="$work/review"
if ! sed '/^\s*```\(json\)\?\s*$/d' "$context/answer" |
  jq -es --arg attribution "**$PROVIDER · $MODEL · $EFFORT**" --arg sha "$HEAD_SHA" '
    if length != 1 then error("expected one answer") else .[0] end |
    select((.summary | type == "string" and length > 0) and (.comments | type == "array")) |
    {commit_id:$sha,event:"COMMENT",body:($attribution + "\n\n" + .summary),
      comments:[.comments[] | {path,line,side,body}]}' > "$context/review.json"; then
  echo "The answer is not a valid review. Raw answer:" >&2
  cat "$context/answer"
  exit 1
fi
jq -r '.body, (.comments[] | "\n---\n\n**\(.path):\(.line)** (\(.side))\n\n\(.body)")' "$context/review.json"
echo -e "\nPrompt, context and answer: $context" >&2
