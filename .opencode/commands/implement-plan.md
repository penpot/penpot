---
description: Execute a ready plan — create issue + branch when on a base branch, or continue on the current branch; implement and commit
agent: build
---

This command is run once a plan is ready (for example, from plan mode). Execute
the plan already prepared in the current session context. This command ends
with exactly one commit. It never pushes — the user pushes.

## 1. Detect the flow (no questions)

Inspect the current branch with `git rev-parse --abbrev-ref HEAD`, pick the
mode, and announce it in one line before acting.

- **On a base branch** (`main`, `develop`, `staging`) → **standalone mode**:
  create the issue and the branch, then implement and commit.
- **On any other branch** (a feature branch, typically `issue-NNNN`) →
  **continue mode**: implement on the current branch and commit. No issue or
  branch is created.

Arguments override detection: `standalone`, `continue`,
`no issue` / `without issue`, or an explicit base such as
`from origin/develop`.

### Standalone mode

1. Create the issue with the **`create-issue`** skill, following the
   *Creating Issues from Draft Body* flow in `mem:workflow/creating-issues`.
   Derive the issue title and body from the plan. Capture the new issue's
   number — call it **NNNN** (needed for the branch name and the commit
   reference).
2. Create the branch from the current HEAD:

   ```
   git checkout -b issue-NNNN
   ```

3. If the arguments say `no issue` / `without issue`, skip the issue and
   create a branch named `plan-<slug>` instead, where `<slug>` is the plan
   title, lowercase and hyphen-separated.

**Standalone while already on a feature branch:** stop and explain that this
would stack branches. Ask the user to re-run with an explicit base, for
example `from origin/develop` — then branch from that base instead of HEAD.

### Continue mode

No issue and no branch. Implement on the current branch. The branch name
provides the issue reference when it follows the `issue-NNNN` pattern.

## 2. Execute the plan

Implement the prepared plan from the session context. Work methodically,
keeping changes focused on what the issue requires. Respect the plan's
proposed parallelization when it applies. Do not commit — the commit happens
in the next step.

## 3. Commit with the create-commit skill

After the implementation is complete, load the **`create-commit`** skill and
follow its workflow to commit the changes. Provide a brief summary of what was
implemented and why, the issue reference (`issue-NNNN`) when there is one, and
the model name you are running as so the `AI-assisted-by` trailer is set
correctly.

Do not push. Pushing is handled separately by the user.

## When you are done

End by suggesting the next steps (suggestions, not a required pipeline — any
instruction from me overrides them):

- `/review-code` — to review the changes just committed; it routes to
  `/make-a-plan` by itself if the findings need one.
- `/open-pr` — when the task is done and the branch is ready to merge.

## User input, overrides and additional context

$ARGUMENTS
