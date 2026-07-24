# Creating Pull Requests

PR only on explicit request. Branch: issue/feature-specific; fallback `<type>/<short-description>` (`fix/...`, `feat/...`, `refactor/...`, `docs/...`, `chore/...`, `perf/...`).

## Target Branch

Auto-detect the base branch with `scripts/detect-target-branch`:

```bash
TARGET=$(scripts/detect-target-branch)
```

This outputs `staging` or `develop` by walking the local commit graph (pure local, no remote/network). Do not ask the user for the target branch unless the tool fails.

## Metadata

Always add the PR to the Main project (`--project "Main"`) unless the user explicitly requests a different project.

## Title Format

PR titles follow commit title conventions:

```
:emoji: Subject line (imperative, capitalized, no period, <=70 chars)
```

See `mem:workflow/creating-commits` for emoji codes. Squash merge uses the PR title as the final commit subject, so title format matters.

## Description Body

Include concise sections covering:
- what changed and why;
- related GitHub issues or Taiga stories (`Fixes #NNNN`, `Relates to #NNNN`, `Taiga #NNNN`);
- screenshots or recordings for UI-visible changes;
- testing performed and residual risk;
- breaking changes or migration notes, if any.

PR descriptions follow this structure:

```markdown
**Note:** This PR was created with AI assistance as part of the Penpot self-improvement initiative.

## What

<the problem or feature and its user-facing impact — short bullet items where there is more than one point>

## Why

<root cause or motivation — a short paragraph or bullets>

## How

<high-level approach and key decisions — bullet items, grouped by area (bold lead-ins) for larger PRs>
```

The "Note:" line is required at the top. Adjust if this is a manual (non-AI) PR.

## Writing Principles

- **Write for humans.** The diff shows what changed. The description explains why.
- **Be concise.** Focus on reasoning: What was the problem? Why did it happen? How did you solve it?
- **Prefer bullets over paragraphs.** Short bullet items, grouped by area with bold lead-ins where helpful, are far easier to digest than prose; keep any remaining paragraph to a few sentences.
- **No manual line wraps.** Markdown renders adapting to the viewport; hard-wrapped lines degrade rendering. One line per paragraph or bullet, however long.
- **Skip the obvious.** Don't explain what `git diff` already shows.

### What NOT to Include

- ❌ List of files changed (visible in diff)
- ❌ Testing steps (CI handles this)
- ❌ Screenshots unless UI-visible
- ❌ Migration notes unless breaking changes
- ❌ Regression fixes introduced during the PR (they're part of the development process, not the feature)

## Before Opening

- Follow `mem:workflow/creating-commits` for commits
- Run the focused tests/lints appropriate to touched modules.
- Do not force-push during review unless the maintainer workflow explicitly asks for it.
- When the user says the code is already pushed, trust that — do not verify remote branch existence via `git ls-remote` or `git fetch`.

## Creating the PR

```bash
cat > /tmp/pr-body.md << 'PR_BODY'
<body content here>
PR_BODY

TARGET=$(scripts/detect-target-branch)

gh pr create \
  --repo penpot/penpot \
  --base "$TARGET" \
  --head <branch> \
  --title "<title>" \
  --project "Main" \
  --body-file /tmp/pr-body.md

rm -f /tmp/pr-body.md
```
