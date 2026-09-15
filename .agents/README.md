# Agentic development with opencode inside devenv

This doc shows how to run AI-assisted development for Penpot inside the
devenv with [opencode](https://opencode.ai). It covers the setup once,
then points to the skills that drive daily work.

Full reference lives in the technical guide:

- [Dev environment](../docs/technical-guide/developer/devenv.md)

This file does not repeat those guides. It gives the short path and
leaves room for notes we add step by step.

## TL;DR

```bash
./manage.sh pull-devenv
./manage.sh run-devenv --ws 0 --attach
```

Then open a shell in the container tmux session, run `opencode` inside
~/penpot directory.

## 1. Introduction

The LLM client — opencode, Claude Code, or Codex — runs in a shell
inside the plain devenv container, with the repo mounted and the
skills in this folder driving the work. One client session per
workspace (`ws0` is the live repo, `ws1+` are sibling clones).

This doc is written around opencode, but Claude Code runs the same
way inside devenv and follows exactly the same flows. This is not
the "agentic devenv" (`--agentic`) from the technical guide, which
runs the client outside devenv and wires it in over MCP — here the
client lives inside the sandboxed devenv docker.

Unlike the agentic devenv, running the client inside the devenv
docker gives it full access to the live environment: every
dependency already resolved by the image, so the agent can write
and run tests directly, query the running PostgreSQL, and reach
the backend and frontend through nREPL — no proxies, no round
trips outside the container.

And if you later want vision, it is one MCP entry away — a
headless Playwright server in your `opencode.json`:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "playwright": {
      "type": "local",
      "command": ["npx", "-y", "@playwright/mcp", "--headless"],
      "enabled": true,
      "env": {}
    }
  }
}
```

## 2. Quickstart: bring up devenv and run opencode inside

Pull once, then bring up the workspace you want (add `--ws 1`,
`--ws 2`, … for more):

```bash
./manage.sh pull-devenv
./manage.sh run-devenv --ws 0 --attach   # ws0 (the live repo)
```

This attaches to the container tmux session. Open a new shell there
(`Ctrl+b c`), `cd` to the repo, and run opencode directly:

```bash
cd penpot;
opencode
```

One session drives exactly one workspace — for N parallel workspaces,
open one container shell and one opencode session per workspace.

Stop with `./manage.sh stop-devenv [--ws N | --all]`. Shared infra
stops when the last workspace stops.


## 3. Connecting providers

### Starting point: Zen free models, no login needed

The easiest way to try the setup is Zen's free models — they need no
login and no key. Just run opencode, pick a free model, and work.

That said, creating a Zen account and connecting with your API key is
still worth it from day one: it unlocks the full model list, spend
limits, auto-reload, and the Go-overflow fallback below. Connect
through the TUI:

- Run `/connect`, pick a provider, paste the key.
- Run `/models` to see what that provider offers.

### OpenCode Go (subscription, best value for daily use)

$10/month subscription with generous included usage — up to $60/month
of model consumption at published per-token rates, depending on the
model. Best value if you work mostly with open coding models
(GLM, Kimi, Qwen, DeepSeek, MiniMax, LongCat…).

The strong part: when you hit a model's monthly limit, Go can fall
back to your Zen balance instead of blocking (enable *Use balance* in
the console). So the setup many of us use is Go + Zen credit on top —
subscription first, pay-as-you-go overflow after.

### OpenCode Zen (pay per use)

Zen is the opencode team's gateway: curated models tested for coding
agents, fair prices, no markups, stable latency. You top up credit
and pay per request, with monthly spend limits and auto-reload.

Free usage is generous: the free models carry limits good enough for
real work, not just a quick taste. Worth knowing: brand-new,
unannounced models often show up on Zen first with a very generous
free quota so people try them — e.g. `0x Alpha`, which later turned
out to be GLM-5.3-Flash. Keep an eye on the free list; the newest
entry is often the best deal.

Two reasons to have a Zen account even with Go:

- It absorbs Go overflow (see above).
- Its free models let you try the whole setup before paying.

### OpenRouter (widest catalog)

If you already have an account, connect it: the widest model range in
one place. Trade-off is latency and occasional instability versus Zen,
which is tuned for coding agents.

Beyond code: OpenRouter also serves image, video, and audio models.
opencode itself cannot call those directly — it is built for code —
but a cheap model can quickly build you a small tool or script that
talks to them through the OpenRouter API. So if you also generate
content other than code and text, having OpenRouter connected is
worth it: the agent wires the plumbing for you.

### OpenAI (subscription or API key)

If you have an OpenAI subscription or API access, connect it — it
works very well as a daily driver alongside (or instead of) Go/Zen.

### Suggested combos

| Profile | Connect |
|---|---|
| Try it out | Nothing (Zen free models, no login) |
| Try it out, properly | Zen account + API key (free models + limits) |
| Daily use, best value | Go + Zen credit (overflow) |
| Widest model choice | Add OpenRouter |
| Already pay OpenAI | Add OpenAI account |

## 4. Recommended models

Personal picks from Andrey, current as of September 2026. Models come
and go, so treat this as a snapshot — the shape (one cheap solver,
one reviewer/planner, one explorer) matters more than the names.

| Model | Role | How often |
|---|---|---|
| Muse Spark 1.3 (`high`) | Main solver: plan, review, develop. Sharp and cheap — covers ~70% of coding tasks. | Daily |
| GLM-5.3-Flash | Reasoning all-rounder, now mostly code/plan reviewer and planner. | Daily |
| DeepSeek V4.1 Flash (`high`) | Explorer: code and idea exploration, sometimes development. Especially good at small bash/node utilities for repo chores and changelog updates. | Daily |
| LongCat 2.0 | Backup solver, occasional stand-in for Muse Spark 1.3. | Weekly |
| GPT-5.6 Luna | Alternative to DeepSeek Flash; pricier, unclear the extra cost pays off. | Rarely |
| Qwen3.8 Flash | As strong as the top three; used in rotation to avoid hammering one model. Less Go subsidy than the top picks, so mostly in overflow mode. | Overflow |
| MiMo-V2.5-Pro | Former main model; slightly pricier now next to Muse Spark / GLM-Flash / LongCat, and less Go subsidy — used in overflow. | Overflow |
| Kimi K3 | Heavy reasoning for hard reviews and plans. Expensive, ~1% of tasks. | Rarely |
| GLM-5.3 | Same slot as Kimi K3: hard reviews and plans only. | Rarely |

**TL;DR:** the first three (Muse Spark 1.3, GLM-5.3-Flash, DeepSeek
V4.1 Flash) are a good starting point.

## 5. Customizing your `opencode.json`

opencode merges config in this order (later wins):

1. Global: `~/.config/opencode/opencode.json` (on host, or the dir
   mounted with `--opencode-config-dir` inside devenv —
   see §9 Advanced usage).
2. Project: `opencode.json` at the repo root (gitignored on purpose —
   use it to override the global entries for one workspace).

Below is a full working example of my personal config at the date of
writing this. It is only an example: define whatever subagents you
need, with whatever models you like or work with.

Copy it to `opencode.json` on the root of the repo:

```json
{
  "$schema": "https://opencode.ai/config.json",
  "disabled_providers": ["amazon-bedrock"],
  "subagent_depth": 2,
  "agent": {
    "compaction": {
      "model": "opencode-go/deepseek-flash",
      "variant": "high"
    },
    "title": {
      "model": "opencode-go/deepseek-flash",
      "variant": "low"
    },
    "explore": {
      "model": "opencode-go/deepseek-flash",
      "variant": "high"
    },
    "build": {
      "prompt": "{file:.agents/prompts/engineer-agent-prompt.md}",
      "permission": {
        "external_directory": {
          "/tmp/**": "allow"
        }
      }
    },
    "general": {
      "prompt": "{file:.agents/prompts/engineer-agent-prompt.md}",
      "permission": {
        "external_directory": {
          "/tmp/**": "allow"
        }
      }
    },
    "engineer-glm": {
      "mode": "subagent",
      "model": "opencode-go/glm-5.3-flash",
      "variant": "high",
      "prompt": "{file:.agents/prompts/engineer-agent-prompt.md}",
      "permission": {
        "*": "allow",
        "task": {
          "*": "allow"
        }
      }
    },
    "engineer-kimi": {
      "mode": "subagent",
      "model": "opencode/kimi-k3",
      "variant": "high",
      "prompt": "{file:.agents/prompts/engineer-agent-prompt.md}",
      "permission": {
        "*": "allow",
        "task": {
          "*": "allow"
        }
      }
    },

    "engineer-qwen": {
      "mode": "subagent",
      "model": "opencode-go/qwen3.7-plus",
      "variant": "high",
      "prompt": "{file:.agents/prompts/engineer-agent-prompt.md}",
      "permission": {
        "*": "allow",
        "task": {
          "*": "allow"
        }
      }
    }
  }
}
```

What the blocks mean:

- `compaction` / `title` / `explore`: cheap background agents. Keep
  them on a fast model; `title` uses the `low` variant on purpose.
- `build` / `general`: the main agents. They load the shared prompt
  `{file:.agents/prompts/engineer-agent-prompt.md}` and may only touch
  `/tmp/**` outside the repo without asking for explicit permision.
- `engineer-*`: one subagent per model family, all with the same
  prompt and full permissions (`"*": "allow"`). They purpose are
  specially for delegate work to them because are defined to be used
  only as subagents.
- `disabled_providers` / `subagent_depth`: global guards. Keep
  `"$schema"` — opencode refuses to start if any field is wrong.

How the `engineer-*` subagents are actually used — delegating work to
them to keep the main context clean — is covered in §6 Common agentic
flows.

Note this is opencode-only: other clients have their own way of
defining subagents or helpers — or none at all.

## 6. Common agentic flows

Work happens two ways: directly in your session, or delegated to a
subagent. Besides the `engineer-*` subagents from §5 there is a
builtin `general` subagent. Delegating planning and review to a
subagent starts a fresh, clean context with a clean prompt instead
of growing the main session — the main lever for keeping context
small. To delegate without switching models, delegate to `general`.

### Issue / error report flow

1. **Frame the problem.** Enter Plan mode (TAB in opencode) and paste the
   report with your intent: "investigate this and find the possible cause",
   "investigate and tell me where this points", or "does this still apply?".
   Explore until you and the agent roughly agree on the problem.
2. **Write the plan.** Run `/make-a-plan` — it executes in Build mode.
   If you need to step in and answer something yourself, press TAB to
   leave Build mode. Use Plan mode only when you want a hard guarantee
   that the agent modifies no file under any circumstance. If you
   explored with a weaker model but want a stronger one to write the
   plan, switch models first or delegate:
   `/make-a-plan delegate to @engineer-glm`.
3. **Iterate on the plan.** The plan is saved to `.agents/plans/`, so you
   never depend on LLM memory: read the file directly, or run `/review-plan`
   for a second opinion (delegation works here too). Complex plans deserve a
   review; simple ones can skip it.
4. **Execute.** Run `/implement-plan`. It first prints the full picture —
   whether it will create an issue and a branch, the execution style, and a
   task checklist — and waits for your go-ahead. Say "step by step" to stop
   after each task (one commit per task) so you can verify as it goes;
   the default runs all tasks with one final commit.
5. **Land the work.** When it finishes, either push yourself and run
   `/create-pr`, or loop `/review-code` → `/make-a-plan` →
   `/implement-plan` until the findings are addressed, then push and
   `/create-pr`. Nothing pushes for you — you always push from your shell.

> Note: `/implement-plan` checks the current branch. On a base branch
> (`main`, `develop`, `staging`) it creates a GitHub issue and a branch
> `issue-NNNN`; on an existing feature branch it continues there and
> creates nothing. The pre-run summary tells you which applies. Read the
> skill at `.agents/skills/implement-plan/SKILL.md` — it is
> self-explanatory.

### Big feature with multiple plans

When the work is too large for a single plan, tell `/make-a-plan`
up front: produce a high-level roadmap where each task will get its
own execution plan, and the roadmap doubles as the progress tracker.

From there the flow mirrors the issue flow above, one level down:
take each roadmap task in turn, write its own plan (`/make-a-plan`,
delegating when it helps), review it when the task is complex
(`/review-plan`), implement it (`/implement-plan`), and mark progress
on the roadmap as you land each piece.

## 7. Connecting `gh` CLI with a token

The `create-issue` and `create-pr` flows need an authenticated `gh`
so they can run on their own. Create a fine-grained token with the
minimum scopes:

1. GitHub → Settings → Developer settings → Personal access tokens →
   Fine-grained tokens → Generate new token.
2. Under Organization permissions, grant access to **Projects**.
3. Under Repository permissions, grant at least **Issues** and
   **Pull requests**.

Then authenticate the CLI and follow the prompts:

```bash
gh auth login
```

Verify with `gh auth status` (token lives in
`~/.config/gh/hosts.yml`). You still push from your own shell — the
agents only read and open issues and PRs.

## 8. Troubleshooting / FAQ

> TBD — filled in step by step as issues come up.

## 9. Advanced usage

### Personal agents and prompts without committing them here

Bind-mount a host dir over the container's `~/.config/opencode`:

```bash
./manage.sh run-devenv --ws 0 --opencode-config-dir ../penpot-opencode
```

It applies at container creation, so changing it needs a stop + rerun
of that instance.

## Summary of available skills

### How the skills are organized

**Flows** are the six skills you invoke by name. Each one covers one step
in the life of a change: plan it, review the plan, implement it, review
the code, open the pull request.

**References** hold the quality standards. A flow's reviewer loads them;
you rarely touch them directly.

**Procedures** define how one concrete step is done — a plan document, an
issue, a commit. Flows call them, but they also work on their own.

**Utilities** are small helpers for everyday work: search, file lookup,
JSON, REPL access, and so on.

### Flows

| Skill | What it does | When you would say |
|---|---|---|
| [`make-a-plan`](skills/make-a-plan/SKILL.md) | Researches the task, writes an implementation plan, asks you the open questions in plain language, and saves the plan to `.agents/plans/`. | "make a plan for the token refresh bug" |
| [`review-plan`](skills/review-plan/SKILL.md) | Evaluates a plan before anyone writes code: completeness, ordering, risks. Approves it or asks for changes. | "review this plan before we start" |
| [`implement-plan`](skills/implement-plan/SKILL.md) | Shows you the full flow first — the issue and branch it will create (or the branch it continues on), the execution style, and the task checklist — and, after your go-ahead, executes a ready plan. Default: every task, one commit. On request ("step by step"): one task, one commit, your confirmation between tasks. On request ("direct"): no issue and no branch, commits on the current branch. | "implement the plan" · "step by step, one commit per task" · "direct, no branch" |
| [`review-code`](skills/review-code/SKILL.md) | Reviews a diff, branch, or PR and returns findings ranked by impact. | "review my changes before I push" |
| [`create-pr`](skills/create-pr/SKILL.md) | Opens a pull request for the current branch — with checks on base branch, commits, issue, and push state — or updates an existing PR's title and description. | "open a PR for this branch" |
| [`resolve-git-conflicts`](skills/resolve-git-conflicts/SKILL.md) | Untangles merge or rebase conflicts: explains both sides, proposes a resolution, applies it after you approve. Never runs `git rebase --continue`. | "resolve these conflicts" |

### References

| Skill | What it holds |
|---|---|
| [`plan-review-criteria`](skills/plan-review-criteria/SKILL.md) | The plan review rubric: six axes, severity levels, approval standard, output format. The `review-plan` reviewer loads it. |
| [`code-review-criteria`](skills/code-review-criteria/SKILL.md) | The code review rubric: five axes, core principles (DRY, KISS, YAGNI), severity format, verdict. The `review-code` reviewer loads it. |

### Procedures

| Skill | What it does |
|---|---|
| [`planner`](skills/planner/SKILL.md) | The spec of a good plan: context, architecture decisions, tasks with acceptance criteria, checkpoints. Used by `make-a-plan`. |
| [`create-issue`](skills/create-issue/SKILL.md) | Creates a GitHub issue that follows Penpot conventions. Used by `implement-plan`; also works on its own. |
| [`create-commit`](skills/create-commit/SKILL.md) | Makes a commit the Penpot way: emoji subject, clear body, `AI-assisted-by` trailer. Used by `implement-plan`; also works alone when you say "commit this". |

### Utilities

| Skill | What it does |
|---|---|
| [`bat-cat`](skills/bat-cat/SKILL.md) | Read files in the terminal with syntax highlighting and line numbers. |
| [`fd-find`](skills/fd-find/SKILL.md) | Find files by name or pattern, respecting `.gitignore`. |
| [`ripgrep`](skills/ripgrep/SKILL.md) | Fast content search with regular expressions. |
| [`jq-json-processor`](skills/jq-json-processor/SKILL.md) | Slice, filter, and reshape JSON output. |
| [`nrepl-eval`](skills/nrepl-eval/SKILL.md) | Run Clojure or ClojureScript code in the live REPL sessions (backend and frontend). |
| [`taiga`](skills/taiga/SKILL.md) | Look up Penpot issues, user stories, and tasks in Taiga. |
| [`testing`](skills/testing/SKILL.md) | The repo's testing rules and TDD workflow, loaded before writing tests. |
| [`local-ci`](skills/local-ci/SKILL.md) | Run CI-style lint, test, and format checks for the modules you touched with `scripts/ci`, and read the logs when they fail. |
| [`security-and-hardening`](skills/security-and-hardening/SKILL.md) | Security checks for code that handles user input, auth, or external services. |
| [`ste`](skills/ste/SKILL.md) | Rewrites prose in Simplified Technical English. Loads only when you name it. |
| [`refine-prompt`](skills/refine-prompt/SKILL.md) | Rewrites a rough prompt into a clearer one. Never runs the prompt. |
| [`update-changelog`](skills/update-changelog/SKILL.md) | Regenerates `CHANGES.md` from a GitHub milestone. |

### A typical round

1. `/make-a-plan` — you get a plan and a saved file in `.agents/plans/`.
2. `/review-plan` — a second opinion; approve or request changes.
3. `/implement-plan` — the code gets written and committed. Starting from a base branch, it also opens the GitHub issue and the `issue-NNNN` branch; the plans that follow continue on that same branch.
4. `/review-code` — a reviewer checks the commit.
5. `/create-pr` — the branch goes up as a pull request.

Every step also works on its own, and you can always say what you want
in plain words — the agents pick the right skill from what you say.

### Adding or changing a skill

Create a folder here with a `SKILL.md` inside. The file needs `name` and
`description` in its frontmatter, and a clear "When to use" section so
agents know when to reach for it. Keep one job per skill, and keep the
two families apart: flows are named with a verb first; reference skills
end in `-criteria`.
