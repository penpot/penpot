---
name: create-issue-from-pr
description: Create a user-facing GitHub issue from a merged PR, separating the WHAT from the HOW, with correct milestone, project, and label assignment.
---

# Skill: create-issue-from-pr

Create a GitHub issue that captures the **WHAT** (user-facing feature or
bug) from an existing PR that describes the **HOW** (implementation).
This is used when the project board needs an issue as the primary
changelog/release unit and the PR alone doesn't suffice.

## When to Use

Use this skill when asked to:

- Create an issue from a PR for changelog tracking purposes
- "Extract" the user-facing problem/feature from a PR's implementation
  details
- Assign a milestone, project, and labels to a newly created issue
  derived from a PR

## Prerequisites

- `gh` CLI authenticated and logged in (`gh auth status`)
- Permission to create issues and edit PRs in the target repository

## Workflow

### 1. Understand the PR

Fetch the PR details (title, body, labels, author, base branch):

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot --json title,body,author,labels,baseRefName,mergedAt,state
```

Identify:

- **WHAT** — The user-facing problem or feature. This goes into the
  issue. The issue should describe symptoms and impact, not internal
  implementation mechanisms.
- **HOW** — The implementation details (sync groups, refactors, etc.).
  These belong in the PR description, not the issue.

### 2. Determine metadata

| Field | Source | Rule |
|-------|--------|------|
| **Title** | PR title | Rewrite from user perspective. Strip leading emoji prefixes like `:bug:`, `:sparkles:`. Focus on observable behavior. Use imperative mood ("Fix...", "Add..."). |
| **Labels** | PR labels | Copy `bug` / `enhancement` / relevant labels from the PR. If PR has `backport candidate` or workflow labels, skip those — only user-facing category labels. |
| **Milestone** | PR milestone | Copy the milestone assigned to the PR. Fetch with: `gh pr view <PR_NUMBER> --repo penpot/penpot --json milestone --jq '.milestone.title'` |
| **Project** | Always `Main` | Penpot uses the `Main` project board (number 8) for all issues. |
| **Body** | PR body's user-facing section | Extract the **steps to reproduce** or **feature description**. Omit implementation details (code changes, internal architecture). Add "Expected behavior" and "Affected versions" sections for bugs. |
| **Issue Type** | PR labels | Map PR's primary label to an Issue Type. `bug` label → `Bug` type, `enhancement` label → `Enhancement` type, Feature/epic → `Feature`. Available types can be listed with: `gh api graphql -f query='query { repository(owner: "penpot", name: "penpot") { issueTypes(first: 20) { nodes { name id } } } }'` |

### 3. Write the issue body

**For bugs** — Use this template:

```markdown
### Summary

<user-facing description — what breaks, what the user experiences>

### Steps to reproduce

1. <step 1>
2. <step 2>
3. <step 3>

### Expected behavior

<what should happen instead>

### Affected versions

<version>
```

**For enhancements** — Use this template:

```markdown
### Summary

<what the user can now do that they couldn't before>

### Use case

<why this is useful, who benefits>
```

### 4. Create the issue

Write the body to a temp file first to avoid shell quoting issues:

```bash
cat > /tmp/issue-body.md << 'ISSUE_BODY'
<issue body here>
ISSUE_BODY
```

Then create:

```bash
gh issue create \
  --repo penpot/penpot \
  --title "<Title>" \
  --label "<label1>" \
  --label "<label2>" \
  --milestone "<milestone>" \
  --project "Main" \
  --body-file /tmp/issue-body.md
```

The command outputs the issue URL, e.g.:
```
https://github.com/penpot/penpot/issues/9527
```

Extract the issue number from the URL.

### 5. Set the Issue Type

`gh issue create` does not support setting the Issue Type directly. You
must set it via the GraphQL API after creation.

First, get the issue's GraphQL node ID:

```bash
ISSUE_ID=$(gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: <ISSUE_NUMBER>) { id }
}}' --jq '.data.repository.issue.id')
```

Then set the type. The available Issue Types and their IDs are:

| Type | ID |
|------|----|
| Task | `IT_kwDOAcyBPM4AX5NY` |
| Bug | `IT_kwDOAcyBPM4AX5Nb` |
| Feature | `IT_kwDOAcyBPM4AX5Nf` |
| Enhancement | `IT_kwDOAcyBPM4B_IQN` |
| Question | `IT_kwDOAcyBPM4B_IQj` |
| Docs | `IT_kwDOAcyBPM4B_IQz` |
| Translations | `IT_kwDOAcyBPM4B_IRA` |
| Self-host | `IT_kwDOAcyBPM4B_IRH` |

Set the type with the `updateIssue` mutation:

```bash
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

Verify the output shows the correct type name.

**Mapping from PR labels:**

| PR label(s) | Issue Type |
|-------------|-----------|
| `bug` | `Bug` |
| `enhancement` | `Enhancement` |
| Feature / epic / `IOP-*` | `Feature` |
| `documentation` / docs-related | `Docs` |
| None of the above / unsure | `Task` |

### 6. Verify the issue

Check that milestone, project, labels, and issue type were applied correctly. Issue type is only visible via GraphQL:

```bash
gh issue view <ISSUE_NUMBER> --repo penpot/penpot \
  --json title,milestone,projectItems,labels \
  --jq '{title, milestone: .milestone.title, projects: [.projectItems[].title], labels: [.labels[].name]}'

# Check Issue Type (not available via gh issue view)
gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: '<ISSUE_NUMBER>') { issueType { name } }
}}' --jq '.data.repository.issue.issueType.name'
```

### 7. Link the PR to the issue (if it isn't already)

Add `Fixes #<ISSUE_NUMBER>` to the PR body to create the "Development"
sidebar link on the issue. This also creates a cross-reference.

First, get the current PR body:

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot --json body --jq '.body'
```

Write the updated body to a temp file (append `\n\nFixes #<ISSUE_NUMBER>`):

```bash
cat > /tmp/pr-body.md << 'PRBODY'
<existing PR body>

Fixes #<ISSUE_NUMBER>
PRBODY
```

Then update:

```bash
gh pr edit <PR_NUMBER> --repo penpot/penpot --body-file /tmp/pr-body.md
```

Verify:

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot --json body --jq '.body | test("Fixes #<ISSUE_NUMBER>")'
```

**Note:** If the PR is already merged (as is typical), adding `Fixes`
will NOT auto-close the issue — it only creates the link in the
"Development" section. This is the desired behavior since the issue
is a tracking/changelog artifact.

### 7. Clean up temp files (optional)

```bash
rm -f /tmp/issue-body.md /tmp/pr-body.md
```

## Examples

### Bug: PR #8724 → Issue #9527

**PR title:** `:bug: Fix content attribute sync group resolution by shape type`

**Issue title:** `Internal error when flattening a mask or path shape inside a component`

| Aspect | PR (HOW) | Issue (WHAT) |
|--------|----------|--------------|
| Title | Implementation mechanism | User-facing symptom |
| Body | Sync groups, resolve-sync-group functions, type-dependent mapping | Steps to reproduce the crash, expected behavior, affected versions |
| Audience | Reviewers, future developers | Users, QA, changelog readers |

### Enhancement example pattern

**PR title:** `:sparkles: Add chunked upload API for large files`

**Issue title:** `Support uploading large media files (>100MB)`

Issue body focuses on the user benefit ("Users can now upload files up
to 500MB") and the problem it solves ("Previously, uploads >100MB
would fail with a 413 error"), not the multipart HTTP implementation.

## Troubleshooting

### Issue Type not working

If `updateIssue` fails with "Field 'issueTypeId' doesn't exist", the
repository may not have Issue Types enabled. Verify:

```bash
gh api repos/penpot/penpot --jq '.has_issues'
```

Issue Types were added to the Penpot repo relatively recently. If
absent, skip this step.

### Shell quoting issues

If the issue body contains special characters (backticks, `$`s,
quotes), always use `--body-file` with a heredoc that has a *quoted*
delimiter (`<< 'EOF'`) to prevent shell expansion.

### Unknown milestone

```bash
gh api repos/penpot/penpot/milestones --jq '.[].title'
```

Pick the highest unreleased version that matches the PR's target
branch cadence.

### Labels not found

```bash
gh api repos/penpot/penpot/labels --jq '.[].name'
```

Common labels: `bug`, `enhancement`, `community contribution`.

## Key Principles

- **Issue = WHAT, PR = HOW.** Never put implementation details in the
  issue body. The issue is for users, QA, and changelog readers.
- **Mirror PR labels** for consistency (same `bug`/`enhancement`
  category).
- **Set Issue Type via GraphQL** — `gh issue create` can't set it.
  Use `updateIssue` mutation with the Issue Type ID after creation.
- **Copy the milestone from the PR.** The PR already has the correct
  milestone assigned — just reuse it. Don't guess based on branch name.
- **Link via PR body** — `Fixes #<ISSUE_NUMBER>` in the PR description
  creates the GitHub "Development" link. Don't rely on comments or
  manual sidebar configuration.
- **One issue per PR** — don't split a single PR's work into multiple
  issues unless the PR itself addresses multiple distinct user-facing
  changes.
