---
name: implement-plan
description: Implementation flow — execute a ready plan from the session context: read the plan, detect the flow, then present the full picture (issue and branch to create or the branch to continue on, execution style, task checklist) and wait for confirmation. Default is every task with one final commit; on request ("step by step"), one task and one commit at a time with a pause after each; on request ("direct"), no issue and no branch — the commit lands on the current branch. Use it when the user asks to implement or execute a plan, in any phrasing.
---

# Implement Plan

This flow is run once a plan is ready (for example, from plan mode). Execute
the plan already prepared in the current session context. It never pushes —
the user pushes.

By default it ends with exactly one commit. When the user asks for it
("step by step"), it commits once per task instead and waits for the
user's confirmation after each one (see *Execution modes*).

## When to use

- The user asks to implement or execute a plan, in any phrasing:
  "implement the plan", "execute it", "go build it" — or runs
  `/implement-plan`.
- A ready, reviewed plan is in the session context or a plan file path
  was given (typically after `/make-a-plan` or `/review-plan`).

Do not use it to produce plans — that is the `make-a-plan` flow.

## 1. Read the plan first

Identify the plan to execute — from the file path the user gave, the
arguments, or the session context. Read it completely. Read the required
memories before writing any code: `mem:critical-info` and the core memory
of every module the plan touches, plus the deeper memories they reference
(AGENTS.md governs this).

## 2. Detect the flow (no questions)

Inspect the current branch with `git rev-parse --abbrev-ref HEAD`, pick the
mode, and announce it in one line before presenting anything. Detection is
read-only: nothing is created until the user confirms (step 3).

- **On a base branch** (`main`, `develop`, `staging`) → **standalone mode**:
  a new GitHub issue and a branch `issue-NNNN` will be created after the
  user's confirmation.
- **On any other branch** (a feature branch, typically `issue-NNNN`) →
  **continue mode**: the implementation continues on the current branch.
  No issue or branch is created. The branch name provides the issue
  reference when it follows the `issue-NNNN` pattern.

Arguments override detection: `standalone`, `continue`, `direct`
(`no branch` / `direct commit`), `no issue` / `without issue`, or an
explicit base such as `from origin/develop`.

**Direct mode** (`direct`, `no branch`, `direct commit`): no issue and
no branch — the implementation and the commit land on the current branch
as it is, even when it is a base branch. Best for small or tooling-only
changes the user wants committed in place.

**Standalone while already on a feature branch:** stop and explain that this
would stack branches. Ask the user to re-run with an explicit base, for
example `from origin/develop` — then branch from that base instead of HEAD.

## 3. Present the checklist and wait

Before touching the repository, show the user the full picture:

- **The flow**: whether the GitHub issue and the branch will be created
  (standalone mode — give the planned branch name, `issue-NNNN` or
  `plan-<slug>`), whether you continue on the current branch
  (continue mode — name it), or whether everything lands on the current
  branch as it is (direct mode — name it, and say so when it is a base
  branch).
- **The execution style**: batch or step-by-step (see *Execution modes*).
- A checklist (todolist) of the plan's tasks, in order.

Then WAIT for the user's explicit confirmation. Do not start until you
have it. If the plan has no discrete tasks, ask the user how to split
it, or propose running it as a single change.

## 4. Execute the plan

**Standalone setup, after the confirmation:** create the issue with the
**`create-issue`** skill, following the *Creating Issues from Draft Body*
flow in `mem:workflow/creating-issues`. Derive the issue title and body
from the plan, capture the new issue's number — call it **NNNN** — and
create the branch from the current HEAD:

```
git checkout -b issue-NNNN
```

If the arguments say `no issue` / `without issue`, skip the issue and
create a branch named `plan-<slug>` instead, where `<slug>` is the plan
title, lowercase and hyphen-separated.

If the arguments say `direct` / `no branch` / `direct commit`, skip the
issue and the branch: implement and commit on the current branch as it
is. If it is a base branch, the checklist presentation already said so —
no further confirmation is needed.

### Batch mode (default)

Implement every task in one go. Work methodically, keeping changes
focused on what the issue requires. Respect the plan's proposed
parallelization when it applies.

When the implementation is complete, load the **`create-commit`** skill
and follow its workflow to commit the changes. Provide a brief summary
of what was implemented and why, the issue reference (`issue-NNNN`) when
there is one, and the model name you are running as so the
`AI-assisted-by` trailer is set correctly.

### Step-by-step mode (on request)

When the user asks for it — "step by step", "task by task", "one commit
per task" — loop one task at a time:

- Execute exactly ONE task.
- Commit it now: load the **`create-commit`** skill and follow it —
  one commit per task, never two tasks in one commit. Same inputs as
  always: what and why, the issue reference, your model name.
- Show the user the result (what changed, files touched, how it was
  verified).
- WAIT for the user's confirmation before starting the next task.

Never batch in this mode: no two tasks in one commit, and no new task
before the user confirms. If a task turns out much bigger than planned,
stop and ask the user before splitting it.

## When you are done

End by suggesting the next steps (suggestions, not a required pipeline — any
instruction from me overrides them):

- `/review-code` — to review the changes just committed; it routes to
  `/make-a-plan` by itself if the findings need one.
- `/create-pr` — when the task is done and the branch is ready to merge.

## User context

Extra context in the user's invocation (the message that triggered this
skill) plays the role command arguments play elsewhere: `standalone`,
`continue`, `direct` (`no branch` / `direct commit`), `no issue` /
`without issue`, an explicit base such as `from origin/develop`, or
`step by step` / `one commit per task` for the step-by-step execution
mode. Modes combine freely, for example "standalone step by step".
