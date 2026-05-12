---
name: update-changelog
description: Find unreleased user-facing commits on develop and add them to CHANGES.md under the proper section with correct attribution.
---

# Skill: update-changelog

Update `CHANGES.md` with issues from a GitHub Project board milestone.
Uses the project board as the authoritative source — not raw git log.

## When to Use

Use this skill when asked to update the changelog for an upcoming
release, review what's missing, or prepare release notes.

## Prerequisites

- `gh` CLI authenticated and logged in (`gh auth status`)
- `scripts/extract-project-issues.py` available (created in this
  session, located at repo root `scripts/`)

## Workflow

### 1. Identify the target milestone

Determine which milestone (version) and project to extract. Ask the
user if unclear. Common defaults:

| Parameter | Default |
|-----------|---------|
| Project | `Main` (number 8) |
| Milestone | Latest unreleased version, e.g. `2.15.0` |
| Status | `Done` |
| Owner | `penpot` |

### 2. Extract issues from the project board

Run the extraction script with Markdown format:

```bash
python3 scripts/extract-project-issues.py \
  --project "Main" \
  --milestone "2.15.0" \
  --status "Done" \
  --format markdown
```

If the script hits an API rate limit (`GraphQL: API rate limit
exceeded`), wait a few minutes and retry. The script fetches PR
author info per linked PR, which consumes quota quickly — avoid
running it back-to-back unnecessarily.

The output contains every issue on the project board with:

| Field | Purpose |
|-------|---------|
| Issue number + title | Identifies the work item |
| Labels | Determines section (`enhancement` → features, `bug` → bugs) |
| Assignees | For reference when writing descriptions |
| Linked PRs (+ author) | Used for attribution `(by @author)` |

### 3. Categorize issues into changelog sections

**`:sparkles: New features & Enhancements`** — Issues with any of:
- Label `enhancement`
- Label starting with `IOP-` (cross-project feature)
- Title starts with `:tada:` or `:sparkles:`
- The work is a new capability, not a defect fix

**`:bug: Bugs fixed`** — Issues with any of:
- Label `bug`
- Title starts with `:bug:`
- The work fixes incorrect behavior (even if no `bug` label — use
  judgment, e.g. "memory leak", "crash", "error")

When an issue has both `bug` and `enhancement` labels, use the primary
label (first listed or most specific). When unsure, assume bug.

### 4. Determine attribution

For each linked PR, the script already fetches the author. Apply
these rules:

| Author context | Attribution |
|----------------|-------------|
| Label includes `community contribution` | **Always** add `(by @username)` using the PR author |
| Author is a core team member (`niwinz`, `Alotor`, `EvaMarco`, `hirunatan`, `luisddm`, `yamila-moreno`, `belen-albeza`, etc.) | No attribution |
| Author is external (unfamiliar username) | Add `(by @username)` |

The `community contribution` label on the issue is the strongest
signal — always attribute those, regardless of who authored the PR.

Place attribution **before** the link:
```
- Description of the fix (by @contributor) [Github #XXXX](url)
```
NOT after the link.

### 5. Write descriptions

Use a concise, user-facing description — not the raw issue title.
Patterns from existing changelog entries:

| Issue title | Changelog description |
|---|---|
| `Large file uploads fail due to HTTP multipart body size cap` | `Add chunked upload API for large media and binary files (removes previous upload size limits)` |
| `bug: shape.applyToken() throws "check error"...` | `Fix Plugin API token methods rejecting JS array of strings` |
| `Binary image data ... extreme memory amplification` | `Reduce memory usage of MCP server when handling images` |

Guidelines:
- Start with a verb (`Add`, `Fix`, `Improve`, `Enhance`, `Update`)
- Describe what was **done**, not what the problem was (for bugs use
  "Fix ...", for features use "Add ..." or "Improve ...")
- Keep it under ~80 chars when possible
- For complex changes with multiple PRs, use a single entry that
  summarizes the net effect

### 6. Add entries to CHANGES.md

Insert entries in the correct `## <version> (Unreleased)` section.

**Format for enhancements:**
```
- Description of the feature [Github #XXXX](https://github.com/penpot/penpot/issues/XXXX)
  (PR: [#NNNN](https://github.com/penpot/penpot/pull/NNNN))
```

**Format for bug fixes:**
```
- Description of the fix (by @contributor) [Github #XXXX](https://github.com/penpot/penpot/issues/XXXX)
  (PR: [#NNNN](https://github.com/penpot/penpot/pull/NNNN))
```

**Key rules:**
- Primary link is always the **ISSUE** URL — the user-facing reference
- PR references are secondary, listed on the next line indented under
  the entry: `(PR: [#NNNN](url), [#NNNN](url))`
- **Only merged PRs** are listed — the extraction script filters out
  open/closed PRs automatically
- When there are multiple PRs, separate with commas:
  `(PR: [#NNNN](url), [#NNNN](url))`
- Use `[Github #XXXX](url)` syntax for the issue
- Attribution before the issue link: `(by @user) [Github #XXXX](url)`
- Entries without attribution simply omit the `(by @...)` part
- One entry per issue (even if multiple PRs fixed it — the issue is
  the single changelog unit)
- Sort entries within each section logically (major features first,
  then smaller enhancements / fixes)

### 7. Handle existing entries

Before adding, check if the issue is already in `CHANGES.md`:

```bash
rg "#<ISSUE_NUMBER>" CHANGES.md
```

If found, skip it (don't duplicate). If the existing description
needs improvement, overwrite the line.

### 8. Verify the result

Read the modified section to ensure:

- All issues from the milestone are represented
- No duplicates
- Attribution is correct and present for community contributions
- Primary link points to the issue (`/issues/`)
- PR references (`/pull/`) only list merged PRs (script handles this)
- Attribution is BEFORE the link, not after
- Format matches surrounding entries

```bash
head -50 CHANGES.md
```

## Troubleshooting

### Rate limit hit during extraction

The script calls `gh pr view` for every linked PR, which counts
against GitHub's API rate limit. If hit:
1. Wait a few minutes for the rate limit to reset
2. Re-run the script

### Script not found

Ensure the working directory is the repo root
(`/home/penpot/penpot`).

### Issue has no linked PRs

Some issues are resolved without code changes (e.g. config changes,
documentation). Include them if user-facing, omit if internal-only.
Apply judgment or ask the user.

## Key Principles

- **Project board is authoritative** — not `git log`. The board
  reflects what was planned, reviewed, and accepted for a milestone.
- **Link issues, not PRs** — the issue is the user-facing unit of
  work; the PR is an implementation detail.
- **Attribution for community contributions only** — core team members
  do not get `(by @...)` tags.
- **One entry per issue** — even if multiple PRs were needed.
- **Check for existing entries** before adding — never duplicate.
