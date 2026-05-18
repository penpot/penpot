---
name: gh-issue-from-pr
description: Create a user-facing GitHub issue from a PR, separating the WHAT from the HOW, with correct milestone, project, labels, and issue type.
---

# Skill: gh-issue-from-pr

Create a GitHub issue that captures the **WHAT** (user-facing feature or
bug) from an existing PR that describes the **HOW** (implementation).
Used when the project board needs an issue as the primary changelog/release unit.

## When to Use

- Create a tracking issue from a PR for changelog purposes
- Extract the user-facing problem/feature from a PR's implementation details
- Assign milestone, project, labels, and issue type to a new issue derived from a PR

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)
- Permission to create issues and edit PRs in the target repository

## Workflow

### 1. Understand the PR

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot \
  --json title,body,author,labels,baseRefName,mergedAt,state,milestone
```

Identify:

- **WHAT** — user-facing problem or feature. Goes into the issue.
  Describe symptoms and impact, not internal mechanisms.
- **HOW** — implementation details. These belong in the PR, not the issue.

### 2. Determine metadata

| Field | Source | Rule |
|-------|--------|------|
| **Title** | PR title | Rewrite from user perspective. Strip leading emoji prefixes (`:bug:`, `:sparkles:`, `:tada:`). Focus on observable behavior. Use imperative mood. |
| **Labels** | PR labels | Copy user-facing labels (`bug`, `enhancement`, `community contribution`). Skip workflow labels (`backport candidate`, `team-qa`). |
| **Milestone** | PR milestone | **Always copy what's on the PR.** Fetch with: `gh pr view <PR_NUMBER> --json milestone --jq '.milestone.title'` If the PR has no milestone, create the issue without one. |
| **Project** | Always `Main` | Penpot uses the `Main` project (number 8) for all issues. |
| **Body** | PR's user-facing section | Extract steps to reproduce or feature description. Omit internal details. Use templates below. |
| **Issue Type** | PR labels / title | Map: `bug` label or `:bug:` title → `Bug`. `enhancement` label or `:sparkles:` title → `Enhancement`. Feature/epic → `Feature`. Default → `Task`. |

### 3. Write the issue body

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

### 4. Create the issue

Write the body to a temp file to avoid shell quoting issues:

```bash
cat > /tmp/issue-body.md << 'ISSUE_BODY'
<body content here>
ISSUE_BODY
```

Create:

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

Output: `https://github.com/penpot/penpot/issues/<NUMBER>`

### 5. Assign to the PR author

Assign the issue to the PR author so they're responsible for it:

```bash
AUTHOR=$(gh pr view <PR_NUMBER> --repo penpot/penpot --json author --jq '.author.login')
gh issue edit <ISSUE_NUMBER> --repo penpot/penpot --add-assignee "$AUTHOR"
```

### 6. Set the Issue Type

`gh issue create` can't set the Issue Type directly. Use GraphQL.

Get the issue's GraphQL node ID:

```bash
ISSUE_ID=$(gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: <ISSUE_NUMBER>) { id }
}}' --jq '.data.repository.issue.id')
```

Issue Type IDs for the Penpot repo:

| Type | ID |
|------|----|
| Bug | `IT_kwDOAcyBPM4AX5Nb` |
| Enhancement | `IT_kwDOAcyBPM4B_IQN` |
| Feature | `IT_kwDOAcyBPM4AX5Nf` |
| Task | `IT_kwDOAcyBPM4AX5NY` |
| Question | `IT_kwDOAcyBPM4B_IQj` |
| Docs | `IT_kwDOAcyBPM4B_IQz` |

Set it:

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

### 7. Verify

```bash
gh issue view <ISSUE_NUMBER> --repo penpot/penpot \
  --json title,milestone,projectItems,labels \
  --jq '{title, milestone: .milestone.title, projects: [.projectItems[].title], labels: [.labels[].name]}'

gh api graphql -f query='
query { repository(owner: "penpot", name: "penpot") {
  issue(number: <ISSUE_NUMBER>) { issueType { name } }
}}' --jq '.data.repository.issue.issueType.name'
```

### 8. Link the PR to the issue

Append `Fixes #<ISSUE_NUMBER>` to the PR body:

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot --json body --jq '.body' > /tmp/pr-body.md
printf "\n\nFixes #<ISSUE_NUMBER>\n" >> /tmp/pr-body.md
gh pr edit <PR_NUMBER> --repo penpot/penpot --body-file /tmp/pr-body.md

# Verify
gh pr view <PR_NUMBER> --repo penpot/penpot --json body \
  --jq '.body | test("Fixes #<ISSUE_NUMBER>")'
```

**Note:** If the PR is already merged, `Fixes` won't auto-close the issue
— it only creates the "Development" sidebar link. This is the desired
behavior since the issue is a tracking artifact.

### 9. Clean up

```bash
rm -f /tmp/issue-body.md /tmp/pr-body.md
```

## Label rules

| PR has | Issue gets |
|--------|-----------|
| `bug` | `bug` |
| `enhancement` | `enhancement` |
| `community contribution` | `community contribution` |
| `backport candidate` | *(skip — workflow label)* |
| `team-qa` | *(skip — workflow label)* |
| No user-facing label | Infer from title: `:bug:` → `bug`, `:sparkles:` → `enhancement` |

## Issue Type mapping

| PR label(s) / title prefix | Issue Type |
|----------------------------|-----------|
| `bug` or `:bug:` | Bug |
| `enhancement` or `:sparkles:` or `:tada:` | Enhancement |
| Feature / epic | Feature |
| Documentation | Docs |
| None of the above | Task |

## Key Principles

- **Issue = WHAT, PR = HOW.** Never put implementation details in the
  issue body. The issue is for users, QA, and changelog readers.
- **Copy the milestone from the PR.** Don't guess based on branch names.
  If the PR has no milestone, create the issue without one.
- **Set Issue Type via GraphQL** — `gh issue create` can't set it.
- **Link via PR body** — `Fixes #<NUMBER>` creates the "Development"
  sidebar link automatically.
- **One issue per PR** — even if a PR fixes multiple things, create a
  single issue that summarizes the overall change.
- **Community attribution:** if the PR has the `community contribution`
  label or the author is not a core team member, add the label to the issue.
