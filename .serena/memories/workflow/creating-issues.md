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

Note: do not soft-wrap paragraphs in the body. Each paragraph is a single line in the source; newlines are reserved for structural breaks (section headers, list items, code-block fences, blank-line separators). List items stay on a single line each. GitHub renders single-line paragraphs correctly, and wrapping makes diffs noisy on every small wording change. Same rule applies to PR bodies.

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

## Creating Issues from PRs

Used when the project board needs an issue as the primary changelog/release
unit and the PR describes the implementation. The issue is the **WHAT**
(user-facing), the PR is the **HOW** (implementation).

### Fetch the PR

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot \
  --json title,body,author,labels,baseRefName,mergedAt,state,milestone
```

Identify:

- **WHAT** — user-facing problem or feature. Goes into the issue.
  Describe symptoms and impact, not internal mechanisms.
- **HOW** — implementation details. These belong in the PR, not the issue.

### Determine metadata

- **Title:** rewrite from user perspective using the title rules above. Strip
  leading emoji prefixes (`:bug:`, `:sparkles:`, `:tada:`). Focus on
  observable behavior.
- **Labels:** copy `community contribution` if present on the PR.
- **Milestone:** always copy what's on the PR.

  ```bash
  gh pr view <PR_NUMBER> --json milestone --jq '.milestone.title'
  ```

  If the PR has no milestone, create the issue without one.
- **Project:** `Main`.
- **Body:** extract the user-facing section (steps to reproduce or feature
  description). Omit internal details. Use the templates above.
- **Issue Type:** use the mapping table above (also handles `:bug:` /
  `:sparkles:` / `:tada:` title prefixes).

### Create the issue

```bash
cat > /tmp/issue-body.md << 'ISSUE_BODY'
<body content here>
ISSUE_BODY

gh issue create \
  --repo penpot/penpot \
  --title "<Title>" \
  --label "community contribution" \  # only if PR has this label
  --milestone "<milestone>" \
  --project "Main" \
  --body-file /tmp/issue-body.md
```

Output: `https://github.com/penpot/penpot/issues/<NUMBER>`

### Assign to the PR author

```bash
AUTHOR=$(gh pr view <PR_NUMBER> --repo penpot/penpot --json author --jq '.author.login')
gh issue edit <ISSUE_NUMBER> --repo penpot/penpot --add-assignee "$AUTHOR"
```

### Set Issue Type and verify

See the **Setting the Issue Type** and **Verification** sections above — the
GraphQL mutations and `gh issue view` calls are identical regardless of how
the issue was sourced.

### Link the PR to the issue

Append `Closes #<ISSUE_NUMBER>` to the PR body:

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot --json body --jq '.body' > /tmp/pr-body.md
printf "\n\nCloses #<ISSUE_NUMBER>\n" >> /tmp/pr-body.md
gh pr edit <PR_NUMBER> --repo penpot/penpot --body-file /tmp/pr-body.md

# Verify
gh pr view <PR_NUMBER> --repo penpot/penpot --json body \
  --jq '.body | test("Closes #<ISSUE_NUMBER>")'
```

**Note:** If the PR is already merged, `Closes` won't auto-close the issue —
it only creates the "Development" sidebar link. This is the desired
behavior since the issue is a tracking artifact.

### Clean up

```bash
rm -f /tmp/issue-body.md /tmp/pr-body.md
```

### Rules for this flow

- **One issue per PR** — even if a PR fixes multiple things, create a single
  issue that summarizes the overall change.
- **Community attribution:** if the PR has the `community contribution`
  label or the author is not a core team member, add the label to the issue.
- **Don't put implementation details in the issue body** — the issue is for
  users, QA, and changelog readers.

## Creating Issues from Draft Body

Used when the user provides a draft body from elsewhere (Taiga story, user
report, discussion transcript) and there is no PR yet.

### Get the body

Read the draft body from wherever it was provided. If the user gives only a
vague one-liner, ask them to expand it (steps to reproduce, expected vs.
actual, use case) before proceeding.

### Derive the title

Apply the title rules in the **Title Derivation** section above. Distinguish
bug vs. feature from the body content:

- Steps to reproduce + expected vs. actual → bug
- "would be nice", "add support for", "allow users to" → feature / enhancement

### Choose a body template

Use the bug or enhancement template from the **Issue Body Template** section
above. Fill in placeholders with the user-provided details. If the body
doesn't fit either, ask the user which template to use.

### Determine metadata

- **Project:** `Main` (always).
- **Milestone:** ask the user if not obvious; otherwise omit.
- **Labels:** usually none for new user-reported issues. Add
  `community contribution` if the user is a non-team contributor.
- **Issue Type:** use the mapping table above (bug description → Bug; feature
  request → Enhancement or Feature).

### Create the issue

```bash
cat > /tmp/issue-body.md << 'ISSUE_BODY'
<body content here>
ISSUE_BODY

gh issue create \
  --repo penpot/penpot \
  --title "<Title>" \
  --label "community contribution" \  # only if applicable
  --milestone "<milestone>" \        # only if provided
  --project "Main" \
  --body-file /tmp/issue-body.md
```

### Set Issue Type and verify

Same GraphQL mutation and `gh issue view` commands as in the
**Setting the Issue Type** and **Verification** sections above.

### Clean up

```bash
rm -f /tmp/issue-body.md
```

## Retitling an Existing Issue

Used when an issue's current title is vague, prefixed, or no longer matches
the body (e.g. `[PENPOT FEEDBACK]: ...`, `feature: ...`).

### Fetch the issue

```bash
gh issue view <NUMBER> --repo penpot/penpot --json title,body
```

### Derive a new title

Read the body (not the current title) and apply the title rules in the
**Title Derivation** section above.

### Apply the new title

```bash
gh issue edit <NUMBER> --repo penpot/penpot --title "<NEW TITLE>"
```

### Confirm

```bash
gh issue view <NUMBER> --repo penpot/penpot --json title
```

## See Also

- End-to-end orchestration entry point: the `create-issue` skill at
  `.opencode/skills/create-issue/SKILL.md`. The skill is a thin entry
  point; this memory is the canonical home for all issue-creation rules.
