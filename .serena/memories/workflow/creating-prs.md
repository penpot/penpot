# Creating Pull Requests

PR only on explicit request. Branch: issue/feature-specific; fallback `<type>/<short-description>` (`fix/...`, `feat/...`, `refactor/...`, `docs/...`, `chore/...`, `perf/...`).

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
**Note:** This PR was created with AI assistance as part of the Penpot MCP self-improvement initiative.

## What

<one paragraph: the problem or feature, user-facing impact>

## Why

<root cause or motivation, why this change was necessary>

## How

<high-level approach, key technical decisions>
```

The "Note:" line is required at the top. Adjust if this is a manual (non-AI) PR.

## Writing Principles

- **Write for humans.** The diff shows what changed. The description explains why.
- **Be concise.** Focus on reasoning: What was the problem? Why did it happen? How did you solve it?
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
