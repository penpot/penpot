---
name: create-pr
description: PR flow — open a new PR for the current task branch (validates base branch, commits, issue and push state) or update an existing PR's title or description to match Penpot conventions. Use it when the user asks to open or create a PR, in any phrasing.
---

# Create PR

Two modes. **Open mode** takes the current task branch to a new, validated
PR. **Update mode** rewrites an existing PR's title or description. Gather
information, validate, and act in one pass. If validation fails, STOP with a
single coherent message that lists every problem and states exactly what
information is missing — never fix or work around problems silently.

Both modes require an authenticated `gh` CLI (`gh auth status`) and never
push — the user pushes from their own shell.

## When to use

- The user asks to open or create a NEW PR for the current task branch, in
  any phrasing ("open a PR", "create the pull request", "put this up for
  review") — or runs `/create-pr`. → **Open mode**.
- The user asks to fix or update an EXISTING PR's title or description to
  match conventions. → **Update mode**.

If the running agent cannot write (for example, the plan agent), say so and
stop — this skill needs the build agent.

## Open mode

### 1. Gather context (read-only)

- Current branch: `git rev-parse --abbrev-ref HEAD`.
- Target base branch: run `./scripts/detect-target-branch` from the repo root.
  It prints the nearest ancestor branch of HEAD (exit 0) or fails (exit 1).
- Commits: `git log --oneline <base>..HEAD`.
- Push state (local): `git rev-parse --verify origin/<branch>` and compare
  with HEAD. It reads the local remote-tracking ref — no network, no SSH. It
  reflects the last push or fetch this clone knows about.
- Issue: from the session context, or from the branch name — `issue-NNNN`
  maps to issue NNNN; recover its title and body with `gh issue view NNNN`.

### 2. Validate — stop with one message if anything fails

Run all checks before reporting, then report every failure together:

1. **Base branch not usable.** If the script fails (exit 1), or its output —
   after stripping an optional `remotes/origin/` prefix — is not one of the
   canonical branches (`develop`, `staging`, `main`), stop and ask the user
   to re-run with more context — for example, passing the base branch
   explicitly in their invocation. An explicit base given by the user
   overrides the script's output.
2. **On a base branch.** There is no task branch to merge — say so and stop.
3. **No commits.** The branch has no commits ahead of the base — say so and
   stop.
4. **No clear issue.** There is no issue in the session context, and the
   branch name has no `issue-NNNN` pattern (or `gh issue view` finds nothing)
   — say so and stop. Exception: the user's invocation says `no issue` /
   `without issue` — then continue without an issue reference.
5. **Branch not pushed.** The remote-tracking ref `origin/<branch>` is
   missing, or `git rev-parse origin/<branch>` differs from HEAD — the
   branch was never pushed, or has commits the remote does not have. Never
   push yourself; ask the user to push and to run `/create-pr` again
   afterwards, then stop.

### 3. Already-open PR

Check whether a PR already exists for this branch (`gh pr list --head
<branch>`). If one exists, report its URL and stop — do not create a second
one. Title or description fixes belong to Update mode.

### 4. Write and create the PR

Write the title and body following `mem:workflow/creating-prs` (title format,
description structure, writing principles) and `mem:workflow/creating-commits`
(commit type emojis). Derive the title and body from the commits and, when
there is one, from the issue body. Reference the issue with `Closes #NNNN`.

```bash
gh pr create --repo penpot/penpot --title "<TITLE>" --body-file /tmp/pr-body.md
```

### 5. Report

Report the PR URL and stop.

## Update mode

1. Identify the PR: the number given by the user, or `gh pr list --head
   <branch>`.
2. Write the new title and/or body following `mem:workflow/creating-prs`.
3. Apply and verify:

```bash
gh pr edit <NUMBER> --repo penpot/penpot --title "<TITLE>" --body-file /tmp/pr-body.md
gh pr view <NUMBER> --repo penpot/penpot --json title,body
```

4. Report and stop.

## User context

Extra context in the user's invocation (the message that triggered this skill)
plays the role command arguments play elsewhere: overrides such as `no issue` /
`without issue`, an explicit base branch (`from origin/staging`), a PR number
for Update mode, and so on.
