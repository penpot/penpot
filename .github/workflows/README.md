# On-demand PR reviews

Start a PR comment with a bare command such as `/claude Review the changed tests.` to use the repository defaults. To override both settings for one review, use `/<reviewer>-<model>-<effort> <request>`. Use `default` for the model’s normal effort. Omit the request for a general review. The commands also work in inline threads and submitted review bodies.

| Reviewer | Repository Actions secret | Optional Actions variables | Example trigger |
| --- | --- | --- | --- |
| Claude Code | `CLAUDE_CODE_OAUTH_TOKEN` from `claude setup-token` (Claude subscription) | `CLAUDE_MODEL`, `CLAUDE_EFFORT` | `/claude-sonnet-5.5-high Review the changed tests.` |
| Copilot CLI | `COPILOT_GITHUB_TOKEN`, a fine-grained PAT with **Copilot Requests** permission (Copilot seat) | `COPILOT_MODEL`, `COPILOT_EFFORT` | `/copilot-gpt-6.2-sol-high Check the boundary cases.` |
| OpenCode Go | `OPENCODE_GO_API_KEY` from the OpenCode Go console | `OPENCODE_MODEL`, `OPENCODE_EFFORT` | `/oc-kimi-k3-default Review this PR.` |

Set secrets and variables under **Settings → Secrets and variables → Actions**. With the GitHub CLI, use `gh secret set NAME` for a credential or `gh variable set NAME --body VALUE` for a default. Without overrides, Claude and Copilot use Sonnet 5.5 and OpenCode uses Kimi K3, each at its CLI’s default effort. GitHub supplies the token for posting reviews.

All three reviewers use Serena for code navigation. The CI-only MCP config defines how to start the image’s bundled `serena` in the checked-out project. A shared adapter translates that config for each reviewer. [CI MCP config](../scripts/pr-review/config/mcp.json) · [Adapter](../scripts/pr-review/render-mcp-config.py)

Each review job installs `playwright-cli` and headless Chromium in its official `penpotapp/devenv` container. On same-repository PRs, reviewers can open a page with `playwright-cli open --browser=chromium <url>` and inspect it with `playwright-cli snapshot`. [Container setup and CLI version](../scripts/pr-review/setup-review-tools.sh)

Only people who can push to the repository can request a review. Commands in the main PR conversation use the workflow on the default branch. To test a new workflow from an inline thread on an older PR, first update the PR branch from its base. For a PR from another fork, post the command in the main conversation. The reviewer reads the proposed changes alongside the base repository’s code. GitHub normally withholds credentials from inline comments and submitted reviews on those PRs.

### Claude Code

Use a family alias (`/claude-sonnet-high`) or a version (`/claude-sonnet-5.5-high` selects `claude-sonnet-5-5`). The workflow runs Claude Code directly. It publishes a review with any changed-line findings, or replies in the original inline thread.

### Copilot CLI

Start with `/copilot` or specify any model enabled for your Copilot seat, such as OpenAI’s `gpt-6.2-sol` or Anthropic’s `claude-sonnet-5.5`. The workflow passes the model ID to Copilot CLI and posts its answer to GitHub. GitHub reserves `@copilot` for its coding agent. [Copilot CLI model reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference#supported-models)

### OpenCode Go

Use `/oc` for a general review or include it in a question, such as `/oc Which test covers this case?`. The command can appear anywhere in the comment and accepts a model and effort, as in `/oc-kimi-k3-default`.

| Request | OpenCode behavior |
| --- | --- |
| Same-repository PR conversation | The native GitHub agent gathers the PR history, handles comment attachments, and posts its summary in the conversation. It can attach changed-line findings. |
| Inline thread | Plain `opencode run` gets the file, line, diff hunk, and discussion. The workflow replies in that thread. |
| Submitted review body or external PR | Plain `opencode run` reads the diff and discussion. The workflow publishes a review with the summary and any inline findings. |

Both paths use the Go provider and a checksum-pinned V1 CLI because V2 lacks `github run`. The plain CLI handles text and diff context where the native agent posts inline answers at the top level, rejects review-body events, or would check out an external contributor’s branch.
