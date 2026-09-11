# Agent skills

This folder is the single home for the skills our coding agents use.
Each skill is a folder with a `SKILL.md` inside — a short instruction
manual that an agent loads only when it needs it.

One copy serves every tool:

- **opencode** reads this folder directly.
- **Claude Code** reads it through the `.claude/skills` symlink.
- **Codex** reads it directly.

To change how the agents behave, edit the `SKILL.md` here. There is no
second copy to keep in sync.

## How the skills are organized

**Flows** are the six skills you invoke by name. Each one covers one step
in the life of a change: plan it, review the plan, implement it, review
the code, open the pull request.

**References** hold the quality standards. A flow's reviewer loads them;
you rarely touch them directly.

**Procedures** define how one concrete step is done — a plan document, an
issue, a commit. Flows call them, but they also work on their own.

**Utilities** are small helpers for everyday work: search, file lookup,
JSON, REPL access, and so on.

## Flows

| Skill | What it does | When you would say |
|---|---|---|
| [`make-a-plan`](skills/make-a-plan/SKILL.md) | Researches the task, writes an implementation plan, asks you the open questions in plain language, and saves the plan to `.agents/plans/`. | "make a plan for the token refresh bug" |
| [`review-plan`](skills/review-plan/SKILL.md) | Evaluates a plan before anyone writes code: completeness, ordering, risks. Approves it or asks for changes. | "review this plan before we start" |
| [`implement-plan`](skills/implement-plan/SKILL.md) | Shows you the full flow first — the issue and branch it will create (or the branch it continues on), the execution style, and the task checklist — and, after your go-ahead, executes a ready plan. Default: every task, one commit. On request ("step by step"): one task, one commit, your confirmation between tasks. On request ("direct"): no issue and no branch, commits on the current branch. | "implement the plan" · "step by step, one commit per task" · "direct, no branch" |
| [`review-code`](skills/review-code/SKILL.md) | Reviews a diff, branch, or PR and returns findings ranked by impact. | "review my changes before I push" |
| [`create-pr`](skills/create-pr/SKILL.md) | Opens a pull request for the current branch — with checks on base branch, commits, issue, and push state — or updates an existing PR's title and description. | "open a PR for this branch" |
| [`resolve-git-conflicts`](skills/resolve-git-conflicts/SKILL.md) | Untangles merge or rebase conflicts: explains both sides, proposes a resolution, applies it after you approve. Never runs `git rebase --continue`. | "resolve these conflicts" |

## References

| Skill | What it holds |
|---|---|
| [`plan-review-criteria`](skills/plan-review-criteria/SKILL.md) | The plan review rubric: six axes, severity levels, approval standard, output format. The `review-plan` reviewer loads it. |
| [`code-review-criteria`](skills/code-review-criteria/SKILL.md) | The code review rubric: five axes, core principles (DRY, KISS, YAGNI), severity format, verdict. The `review-code` reviewer loads it. |

## Procedures

| Skill | What it does |
|---|---|
| [`planner`](skills/planner/SKILL.md) | The spec of a good plan: context, architecture decisions, tasks with acceptance criteria, checkpoints. Used by `make-a-plan`. |
| [`create-issue`](skills/create-issue/SKILL.md) | Creates a GitHub issue that follows Penpot conventions. Used by `implement-plan`; also works on its own. |
| [`create-commit`](skills/create-commit/SKILL.md) | Makes a commit the Penpot way: emoji subject, clear body, `AI-assisted-by` trailer. Used by `implement-plan`; also works alone when you say "commit this". |

## Utilities

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

## A typical round

1. `/make-a-plan` — you get a plan and a saved file in `.agents/plans/`.
2. `/review-plan` — a second opinion; approve or request changes.
3. `/implement-plan` — the code gets written and committed. Starting from a base branch, it also opens the GitHub issue and the `issue-NNNN` branch; the plans that follow continue on that same branch.
4. `/review-code` — a reviewer checks the commit.
5. `/create-pr` — the branch goes up as a pull request.

Every step also works on its own, and you can always say what you want
in plain words — the agents pick the right skill from what you say.

## Adding or changing a skill

Create a folder here with a `SKILL.md` inside. The file needs `name` and
`description` in its frontmatter, and a clear "When to use" section so
agents know when to reach for it. Keep one job per skill, and keep the
two families apart: flows are named with a verb first; reference skills
end in `-criteria`.
