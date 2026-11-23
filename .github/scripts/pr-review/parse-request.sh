#!/usr/bin/env bash
set -euo pipefail
# Claude and Copilot commands start the comment. OpenCode accepts /oc
# as a whole token anywhere in the body, with or without a model and effort.
command=$(jq -r '(.comment.body // .review.body // "") |
  if test("^/(claude|copilot)(?=[-\\s]|$)") then capture("^(?<cmd>\\S+)").cmd
  else [scan("(?:^|\\s)(/oc(?:-[^\\s]+)?)(?=\\s|$)")][0][0] // empty end' "$GITHUB_EVENT_PATH")
[[ -n $command ]] || exit 0
user=$(jq -r '.comment.user.login // .review.user.login' "$GITHUB_EVENT_PATH")
access=$(gh api "repos/$GITHUB_REPOSITORY/collaborators/$user/permission" --jq .permission)
case "$access" in admin|maintain|write) ;; *) echo "Write access required."; exit 1 ;; esac

provider=${command#/}
provider=${provider%%-*}
case "$command" in
  /claude) model=$DEFAULT_CLAUDE_MODEL; effort=$DEFAULT_CLAUDE_EFFORT ;;
  /copilot) model=$DEFAULT_COPILOT_MODEL; effort=$DEFAULT_COPILOT_EFFORT ;;
  /oc) model=${DEFAULT_GO_MODEL#opencode-go/}; effort=$DEFAULT_GO_EFFORT ;;
  /*-*-*) selection=${command#*-}; model=${selection%-*}; effort=${selection##*-} ;;
  *) echo "Use /<reviewer> or /<reviewer>-<model>-<effort>."; exit 1 ;;
esac
if [[ ! $model =~ ^[a-z0-9]+([.-][a-z0-9]+)*$ || ! $effort =~ ^(default|minimal|low|medium|high|xhigh|max)$ ]]; then
  echo "Invalid model or effort for $provider."; exit 1
fi
case "$provider" in
  claude) [[ $model != claude-* ]] || model=${model#claude-}
          [[ $model != *-* ]] || model="claude-${model//./-}" ;;
  oc) provider=opencode; model="opencode-go/$model" ;;
esac
pr=$(jq -r '.pull_request.number // .issue.number' "$GITHUB_EVENT_PATH")
data=$(gh api "repos/$GITHUB_REPOSITORY/pulls/$pr")
head=$(jq -r .head.sha <<< "$data")
# A fork can supply a diff, but never the checkout that loads plugins or runs checks.
checkout=$(jq -r --arg repo "$GITHUB_REPOSITORY" 'if .head.repo.full_name == $repo
  then .head.sha else .base.sha end' <<< "$data")
native=false
if [[ $provider == opencode && $GITHUB_EVENT_NAME == issue_comment ]]; then
  native=$(jq -r --arg repo "$GITHUB_REPOSITORY" '.head.repo.full_name == $repo' <<< "$data")
fi
printf '%s\n' "provider=$provider" "model=$model" "effort=$effort" "pr=$pr" \
  "head=$head" "checkout=$checkout" "native=$native" "mention=$command" >> "$GITHUB_OUTPUT"
