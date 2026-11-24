---
name: create-pr
description: PR flow — open a new PR for the current task branch or update an existing PR's title or description to match Penpot conventions. Use it when the user asks to open or create a PR, in any phrasing.
---

# Skill: create-pr

Entry point for all GitHub PR work. Authentication, base-branch detection,
push state and the existing-PR check are handled by `python3 scripts/gh.py
create-pr` and by `gh` itself — do not perform them here. The canonical rules
for titles, bodies, merge strategy and issue linking live in
`mem:workflow/creating-prs` (file:
`.serena/memories/workflow/creating-prs.md`). This skill routes to the right
flow.

## When to Use

- **Open mode** — the user asks to open or create a NEW PR for the current
  task branch, in any phrasing ("open a PR", "create the pull request",
  "put this up for review") — or invokes `@create-pr`.
  → **Open mode** below.
- **Update mode** — the user asks to fix or update an existing PR's title or
  description. → **Update mode** below.

If the running agent cannot write (for example, the plan agent), say so and
stop — this skill needs the build agent.

The user pushes from their own shell. Never push, force-push or modify a
remote. If the command reports that the branch was not pushed, tell the user
to push it and invoke the skill again.

For draft PRs, warn that CI does not run on them and only add `--draft` when
the user agrees.

## Open mode

1. Write the title and the body following `mem:workflow/creating-prs`:
   `:emoji:` title, the `**Note:**` line, and the `## What` / `## Why` /
   `## How` sections. Derive them from the commits and the issue body when
   there is one. Repeat the `AI-assisted-by:` trailer once per model that
   worked on the branch. Put the body in a temp file to avoid shell quoting.
2. Decide whether the PR resolves an issue. If it does, include `Closes
   #NNNN` in the body for human readers and pass `--issue NNNN` so the
   command creates the GitHub link. If the user's invocation says `no issue`
   / `without issue`, pass no `--issue`.
3. Run the command:

```bash
python3 scripts/gh.py create-pr \
  --title "<TITLE>" --body-file /tmp/pr-body.md [--issue NNNN] [--base BASE]
```

`--base` is optional: by default the command detects the nearest ancestor
branch of `HEAD`, ported from `scripts/detect-target-branch`. Pass it only
when the detected base is not the one you want. `--head` defaults to the
current branch and `--draft` marks the PR as draft.

If the command fails with `detected base '<name>' is not a local branch; pass
--base explicitly`, rerun it with `--base <name>` using the name from the
error. Do not ask the user and do not add pre-flight checks for this case.

4. Report the URL from the command's output and stop. If it fails, report
   the `gh` error as-is; do not add local pre-flight checks.

## Update mode

1. Identify the PR: the number given by the user, or `gh pr list --head
   <branch>`.
2. Write the new title and/or body following `mem:workflow/creating-prs`.
3. Apply and verify:

```bash
gh pr edit <NUMBER> --repo penpot/penpot --title "<TITLE>" --body-file /tmp/pr-body.md
gh pr view <NUMBER> --repo penpot/penpot --json title,body
```

If the updated body contains `Closes #NNNN`, run the explicit assignment
command from `mem:workflow/creating-prs`:

```bash
python3 scripts/gh.py link-issue <ISSUE_NUMBER> <PR_NUMBER>
```

4. Report and stop.

## User context

Extra context in the user's invocation (the message that triggered this
skill) plays the role command arguments play elsewhere: overrides such as
`no issue` / `without issue`, an explicit base branch (`from origin/staging`),
a PR number for Update mode, and so on.
