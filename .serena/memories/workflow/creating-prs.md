# Creating Pull Requests

PR only on explicit request.

## Branch Naming

- Primary: `issue-NNNN` — one branch per GitHub issue (e.g. `issue-11525`).
- No issue: free-form descriptive name, dash-separated, no slashes (e.g. `fix-ellipse-icon-typo`, `feat-auto-link-libraries`).
- If the user already created the branch, use it as-is — never rename.

## Target Branch

The `create-pr` shortcut detects the base branch automatically: it ports the
heuristic of `scripts/detect-target-branch` into `detect_target_branch()` in
`scripts/gh.py` (local commit graph, no remote/network), usually yielding
`staging` or `develop`. Do not ask the user for the target branch unless the
command fails. Pass `--base` only when the detected branch is not the one
wanted; explicit user context overrides the detection. If detection fails
with `detected base '<name>' is not a local branch; pass --base explicitly`,
rerun with `--base <name>` using the name from the error; do not ask the
user and do not add pre-flight checks for this case.

## Metadata

Always add the PR to the Main project (`--project "Main"`) unless the user explicitly requests a different project.

## Title Format

PR titles follow commit title conventions:

```
:emoji: Subject line (imperative, capitalized, no period, <=80 chars)
```

See `mem:workflow/creating-commits` for emoji codes. Squash merge uses the PR title as the final commit subject, so title format matters.

## Merge Strategy

Policy: `CONTRIBUTING.md` → "Merging", under "Pull Requests".

- Squash merge is the default, so the PR's own commits never reach `main`
  and the PR title becomes the landed subject.
- A regular merge is possible on request only. The request is the **first
  line** of the PR body, above the `**Note:**` line:
  `> **Merge:** do not squash; each commit is self-contained.`
- Ask for it only when every commit on the branch is self-contained (builds
  and passes lint on its own). Default to squash unless the user asks for
  the opposite.

## Description Body

Include concise sections covering:
- what changed and why;
- related GitHub issues or Taiga stories (`Closes #NNNN` for issues resolved by the PR, `Relates to #NNNN` for context, `Taiga #NNNN`); use the explicit issue assignment process below for each `Closes` issue;
- screenshots or recordings for UI-visible changes;
- testing performed and residual risk;
- breaking changes or migration notes, if any.

PR descriptions follow this structure:

```markdown
**Note:** This PR was created with AI assistance.

## What

<the problem or feature and its user-facing impact — short bullet items where there is more than one point>

## Why

<root cause or motivation — a short paragraph or bullets>

## How

<high-level approach and key decisions — bullet items, grouped by area (bold lead-ins) for larger PRs>
```

The "Note:" line is required at the top. Adjust if this is a manual (non-AI) PR.
When the PR requests a regular merge instead of a squash merge, its
`**Merge:**` line goes above the `**Note:**` line (see Merge Strategy).

## Writing Principles

- **Write for humans.** The diff shows what changed. The description explains why.
- **Be concise.** Focus on reasoning: What was the problem? Why did it happen? How did you solve it?
- **Prefer bullets over paragraphs.** Short bullet items, grouped by area with bold lead-ins where helpful, are far easier to digest than prose; keep any remaining paragraph to a few sentences.
- **No manual line wraps.** Markdown renders adapting to the viewport; hard-wrapped lines degrade rendering. One line per paragraph or bullet, however long. This applies to PR descriptions and to PR/issue comments and review replies alike.
- **Skip the obvious.** Don't explain what `git diff` already shows.

### What NOT to Include

- ❌ List of files changed (visible in diff)
- ❌ Testing steps (CI handles this)
- ❌ Screenshots unless UI-visible
- ❌ Migration notes unless breaking changes
- ❌ Regression fixes introduced during the PR (they're part of the development process, not the feature)

## Explicit Issue Assignment

- For each GitHub issue that a PR resolves, run `python3 scripts/gh.py link-issue <ISSUE_NUMBER> <PR_NUMBER>` after creating or editing the PR. Do not rely on `Closes #NNNN` in the body; it is only human-readable context.
- The command calls `addCloseIssueReferences` and trusts the successful mutation: GitHub does not reliably report mutation-created links back through `closedByPullRequestsReferences(userLinkedOnly: true)`, so the command exits non-zero only when a link target is missing or the mutation fails. It is safe to rerun and also works for an already merged PR. Do not rely on it to keep the issue open: adding `Closes #N` to the body of an already-merged PR closed the issue immediately when tried (#12896 / PR #12067, 2026-11-09).
- Skip this process for `Relates to #NNNN` and Taiga references, which do not represent a closing relationship.

## Before Opening

- Follow `mem:workflow/creating-commits` for commits
- Run the focused tests/lints appropriate to touched modules.
- Do not force-push during review unless the maintainer workflow explicitly asks for it.
- When the user says the code is already pushed, trust that — do not verify remote branch existence via `git ls-remote` or `git fetch`.

## Creating the PR

Write the body to a temp file and run the shortcut. It detects the base, runs
`gh pr create` with `--project Main`, parses the PR number and, with
`--issue N`, creates the explicit issue link (see `mem:scripts/gh`):

```bash
cat > /tmp/pr-body.md << 'PR_BODY'
<body content here>
PR_BODY

python3 scripts/gh.py create-pr \
  --title "<title>" --body-file /tmp/pr-body.md \
  [--issue NNNN] [--base <branch>] [--project <project>]

rm -f /tmp/pr-body.md
```

Do not pre-check authentication, push state or an existing PR: the command
and `gh` report those errors. Never push yourself; ask the user if the
command reports that the branch was not pushed.
