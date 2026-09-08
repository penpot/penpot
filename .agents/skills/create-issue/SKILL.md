---
name: create-issue
description: Create or update GitHub issues (from PR, from draft body, retitle existing). Routes to the canonical flow in `mem:workflow/creating-issues`.
---

# Skill: create-issue

Entry point for all GitHub issue work. All rules (title derivation, metadata,
body templates, Issue Type IDs), all flows, and all `gh` / GraphQL commands
live in `mem:workflow/creating-issues` (file:
`.serena/memories/workflow/creating-issues.md`). This skill routes to the
right flow.

## When to Use

- **Create from PR** — PR exists; the issue is the changelog/release unit,
  the PR is the implementation. Issue = WHAT, PR = HOW.
  → memory section **Creating Issues from PRs**
- **Create from draft body** — Taiga story, user report, discussion; no PR
  yet.
  → memory section **Creating Issues from Draft Body**
- **Retitle existing issue** — current title is vague, prefixed, or stale.
  → memory section **Retitling an Existing Issue**

Everything else (title derivation, metadata policy, body templates, Issue
Type IDs, create/verify/cleanup commands) lives in the memory — go to the
matching section there.
