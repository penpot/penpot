---
name: create-pr
description: Create or update a GitHub PR following Penpot conventions.
---

# Skill: create-pr

Create or update a GitHub PR. Read and follow:
- `mem:workflow/creating-prs` — title format, description structure, writing principles
- `mem:workflow/creating-commits` — commit type emojis

## When to Use

- Creating a new PR from a feature branch
- Updating an existing PR's title or description to match conventions

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)

## Commands

**Create:**

```bash
gh pr create --repo penpot/penpot --title "<TITLE>" --body-file /tmp/pr-body.md
```

**Update:**

```bash
gh pr edit <NUMBER> --repo penpot/penpot --title "<TITLE>" --body-file /tmp/pr-body.md
```

**Verify:**

```bash
gh pr view <NUMBER> --repo penpot/penpot --json title,body
```
