---
name: issue-title
description: Derive a clear, well-formatted title for a GitHub issue from its description body, using descriptive present-tense for bugs and imperative mood for features, always including the "where" (location in the UI/module).
---

# Skill: issue-title

Derive a concise, descriptive title for a GitHub issue based on its body
content. Use **descriptive present tense for bugs** (e.g. "Plugin API
crashes when setting text fills") and **imperative mood for features** (e.g.
"Add customizable dash and gap controls"). No emoji or type prefixes
(`feat:`, `bug:`, `feature:`, etc.).

Can be used both when **creating a new issue** and when **updating an
existing one** that has a vague or outdated title.

## When to Use

- Creating a new issue and need a well-formatted title from the draft body
- An existing issue has a vague, outdated, or auto-generated title (e.g.
  `[PENPOT FEEDBACK]: ...`, `feature: ...`)
- The current title doesn't reflect the actual content of the description
- The title is missing the "where" (which part of the UI/module is affected)

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)

## Workflow

### 1. Get the issue body

For an **existing issue**, fetch it:

```bash
gh issue view <NUMBER> --repo penpot/penpot --json title,body
```

For a **new issue**, read the draft body from wherever it was provided
(Taiga link, user report, discussion, etc.).

### 2. Read the body and derive a title

Extract the core problem or request from the description. Distinguish between
bug reports and feature requests:

**Bug titles (descriptive, present tense):**
Describe the symptom as it appears to the user. Format:
`[Where] [present-tense verb] when [condition]`

- *"Plugin API crashes when setting text fills"*
- *"Canvas renders glitches when zooming quickly"*
- *"French Canada locale falls back to French (fr) translations"*
- *"Text layer content is not deleted when WebGL render is enabled"*

Do **not** start bug titles with "Fix" or any imperative verb. The title
should state what's broken, not command a fix.

**Feature / Enhancement titles (imperative mood):**
Command what should be built. Format:
`[Imperative verb] [what] in/on [where]`

- *"Add customizable dash and gap length controls to dashed strokes in the sidebar"*
- *"Show user, timestamp, and hash in the workspace history panel like git commits"*
- *"Validate shape on add-object to catch malformed inputs early"*

**Universal rules (both types):**
- **Include the "where"** — specify the UI location or module (e.g.
  "in the sidebar", "in the workspace history panel", "on the stroke
  options")
- **No prefixes** — strip `bug:`, `feature:`, `feat:`, `:bug:`, `:sparkles:`,
  `[PENPOT FEEDBACK]`, etc.
- **No emoji** — plain text only
- **Be specific** — prefer concrete detail over generality. If the
  description mentions two related problems, capture both.

**Examples:**

| Original / draft title | Type | New title |
|---|---|---|
| `[PENPOT FEEDBACK]: WebGL` | Bug | `Canvas renders glitches when zooming quickly — text appears distorted and nodes have background-colored rectangles` |
| `bug: flatten-nested-tokens-json uses $type instead of $value as the DTCG token/group discriminator` | Bug | `Token import fails when group-level type inheritance is used — parser misidentifies groups as tokens` |
| `feature: Dashed stroke customization` | Feature | `Add customizable dash and gap length controls to dashed strokes in the sidebar` |
| `feature: Add more detail to history of actions` | Feature | `Show user, timestamp, and hash in the workspace history panel like git commits` |

### 3. Apply the title

**If updating an existing issue:**

```bash
gh issue edit <NUMBER> --repo penpot/penpot --title "<NEW TITLE>"
```

**If creating a new issue:**

```bash
gh issue create --repo penpot/penpot --title "<NEW TITLE>" --body "<BODY>"
```

### 4. Confirm

For updates, the command returns the issue URL. Verify by optionally fetching
again:

```bash
gh issue view <NUMBER> --repo penpot/penpot --json title
```

## Key Principles

- **Bug titles describe the symptom** — present tense, 3rd person:
  "crashes", "fails", "shows", "is cut off", "does not load". Do not
  start with "Fix" or "Bug:".
- **Feature titles use imperative mood** — command form: "Add", "Show",
  "Use", "Validate", "Support", "Toggle".
- **Always include the "where"** — a title like "Crashes when zooming"
  is too vague; "Canvas crashes when zooming quickly" is clear.
- **No prefixes, no emoji** — strip all type labels and decorative
  characters from the title.
- **Derive from the body, not the current title** — the body contains
  the real detail; the current title may be auto-generated or stale.
- **Two problems → cover both** — if the description has two distinct
  but related issues, capture both in the title joined by "and".
