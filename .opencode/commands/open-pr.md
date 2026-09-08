---
description: Open the PR for the current task branch — detects the base branch, requires a clear issue, never pushes
agent: build
---

Open the pull request for the current task branch. Gather information,
validate, and create the PR in one pass. If validation fails, STOP with a
single coherent message that lists every problem and states exactly what
information is missing — never fix or work around problems silently.

## 1. Gather context (read-only)

- Current branch: `git rev-parse --abbrev-ref HEAD`.
- Target base branch: run `./scripts/detect-target-branch` from the repo root.
  It prints the nearest ancestor branch of HEAD (exit 0) or fails (exit 1).
- Commits: `git log --oneline <base>..HEAD`.
- Remote state: `git ls-remote origin <branch>`.
- Issue: from the session context, or from the branch name — `issue-NNNN`
  maps to issue NNNN; recover its title and body with `gh issue view NNNN`.

## 2. Validate — stop with one message if anything fails

Run all checks before reporting, then report every failure together:

1. **Base branch not usable.** If the script fails (exit 1) or its output is
   not one of the canonical branches (`develop`, `staging`, `main`), stop and
   ask the user to re-run with more context — for example, passing the base
   branch explicitly in the arguments. An explicit base given in the
   arguments overrides the script's output.
2. **On a base branch.** There is no task branch to merge — say so and stop.
3. **No commits.** The branch has no commits ahead of the base — say so and
   stop.
4. **No clear issue.** There is no issue in the session context, and the
   branch name has no `issue-NNNN` pattern (or `gh issue view` finds nothing)
   — say so and stop. Exception: the arguments say `no issue` /
   `without issue` — then continue without an issue reference.
5. **Branch not pushed.** `git ls-remote origin <branch>` finds nothing —
   never push yourself; ask the user to push and to re-run `/open-pr`
   afterwards, then stop.

## 3. Already-open PR

Check whether a PR already exists for this branch (`gh pr list --head
<branch>`). If one exists, report its URL and stop — do not create a second
one.

## 4. Create the PR

Load the **`create-pr`** skill and follow its workflow
(`mem:workflow/creating-prs` has the title format and body structure). Derive
the title and body from the commits and, when there is one, from the issue
body. Reference the issue with `Closes #NNNN`.

## 5. Report

Report the PR URL and stop.

## User input, overrides and additional context

$ARGUMENTS
