# Creating Pull Requests

PR only on explicit request. Branch: issue/feature-specific; fallback `<type>/<short-description>` (`fix/...`, `feat/...`, `refactor/...`, `docs/...`, `chore/...`, `perf/...`).

## Title Format

PR titles follow commit title conventions:

```
:emoji: Subject line (imperative, capitalized, no period, <=70 chars)
```

See `mem:workflow/creating-commits` for emoji codes. Squash merge uses the PR title as the final commit subject, so title format matters.

## Description

Include concise sections covering:
- what changed and why;
- related GitHub issues or Taiga stories (`Fixes #NNNN`, `Relates to #NNNN`, `Taiga #NNNN`);
- screenshots or recordings for UI-visible changes;
- testing performed and residual risk;
- breaking changes or migration notes, if any.

For the Penpot MCP self-improvement workflow, PR descriptions are expected to start with:

> **Note:** This PR was created with AI assistance as part of the Penpot MCP self-improvement initiative.

## Before Opening

- Follow `mem:workflow/creating-commits` for changelog expectations.
- Run the focused tests/lints appropriate to touched modules.
- Do not force-push during review unless the maintainer workflow explicitly asks for it.