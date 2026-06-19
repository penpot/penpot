# Creating Issues

Create GitHub issues only on explicit request. Use `gh` CLI authenticated to `penpot/penpot`.

## Title Derivation

Derive the title from the source material (bug report, user feedback, feature request, etc.) — not from any pre-existing title which may be auto-generated or stale.

### Bug titles (descriptive present tense)

Describe the symptom as it appears to the user. Format: `[Where] [present-tense verb] when [condition]`.

- *"Plugin API crashes when setting text fills"*
- *"Canvas renders glitches when zooming quickly"*
- *"French Canada locale falls back to French (fr) translations"*

Do **not** start bug titles with "Fix" or any imperative verb — state what's broken, not command a fix.

### Feature / Enhancement titles (imperative mood)

Command what should be built. Format: `[Imperative verb] [what] in/on [where]`.

- *"Add customizable dash and gap length controls to dashed strokes in the sidebar"*
- *"Show user, timestamp, and hash in the workspace history panel like git commits"*

### Universal rules

- **Include the "where"** — specify the UI location or module (e.g. "in the sidebar", "on the stroke options")
- **No prefixes** — strip `bug:`, `feature:`, `feat:`, `:bug:`, `:sparkles:`, `[PENPOT FEEDBACK]`, etc.
- **No emoji** — plain text only
- **Be specific** — prefer concrete detail over generality
- **Two problems → cover both** — if the description has two distinct but related issues, capture both joined by "and"

## Metadata

| Field | Rule |
|-------|------|
| **Labels** | `bug` (crashes/regressions) · `enhancement` (new features) · `community contribution` (PRs from non-core) · skip workflow labels (`backport candidate`, `team-qa`) |
| **Milestone** | Use the current or next planned milestone. Fetch available milestones: `gh api repos/penpot/penpot/milestones --jq '.[].title'`. If unsure, omit. |
| **Project** | Always `Main` (project number 8). Use `--project "Main"` flag. |
| **Issue Type** | See Issue Type section below. Cannot be set via `gh issue create` — use GraphQL after creation. |

## Issue Body Template

Write the body to a temp file to avoid shell quoting issues:

**Bug template:**
```markdown
### Description

<what breaks, what the user experiences>

### Steps to reproduce

1. <step 1>
2. <step 2>

### Expected behavior

<what should happen instead>

### Affected versions

<version>
```

**Enhancement template:**
```markdown
### Description

<what the user can now do that they couldn't before>

### Use case

<why this is useful, who benefits>

### Affected versions

<version>
```

## Creating the Issue

```bash
cat > /tmp/issue-body.md << 'ISSUE_BODY'
<body content here>
ISSUE_BODY

gh issue create \
  --repo penpot/penpot \
  --title "<Derived title>" \
  --label "<label>" \
  --project "Main" \
  --body-file /tmp/issue-body.md
```

Output: `https://github.com/penpot/penpot/issues/<NUMBER>`

## Setting the Issue Type

`gh issue create` can't set Issue Type directly. Use GraphQL after creation.

**Issue Type IDs for penpot/penpot:**

| Type | ID |
|------|----|
| Bug | `IT_kwDOAcyBPM4AX5Nb` |
| Enhancement | `IT_kwDOAcyBPM4B_IQN` |
| Feature | `IT_kwDOAcyBPM4AX5Nf` |
| Task | `IT_kwDOAcyBPM4AX5NY` |
| Question | `IT_kwDOAcyBPM4B_IQj` |
| Docs | `IT_kwDOAcyBPM4B_IQz` |

**Map:**
- `bug` label → Bug
- `enhancement` label → Enhancement
- Feature/epic → Feature
- Docs → Docs
- None of the above → Task

**Set it:**
```bash
ISSUE_ID=$(gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: <NUMBER>) { id }
}}' --jq '.data.repository.issue.id')

gh api graphql -f query='
mutation {
  updateIssue(input: {
    id: "'"$ISSUE_ID"'"
    issueTypeId: "<TYPE_ID>"
  }) {
    issue { number issueType { name } }
  }
}'
```

## Verification

```bash
gh issue view <NUMBER> --repo penpot/penpot \
  --json title,labels,milestone,projectItems \
  --jq '{title, milestone: .milestone.title, labels: [.labels[].name], projects: [.projectItems[].title]}'

gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: <NUMBER>) { issueType { name } }
}}' --jq '.data.repository.issue.issueType.name'
```

## Cleanup

```bash
rm -f /tmp/issue-body.md
```

## See Also

- Creating issues **from PRs** (separating WHAT from HOW): `mem:workflow/creating-prs`
