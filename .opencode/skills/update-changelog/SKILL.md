---
name: update-changelog
description: Update the project CHANGES.md with issues from a given GitHub milestone, with correct categorization and references.
---

# Skill: update-changelog

Update `CHANGES.md` with entries for all issues and PRs in a given GitHub
milestone. Each entry references the user-facing issue (not the PR) as the
primary link, with the fix PR on a sub-line.

## When to Use

- Before a new release, to populate the changelog with all fixed issues
- When new issues are added to an existing milestone and the changelog needs
  to be refreshed
- To ensure every entry follows the correct format for the changelog

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)
- Read access to the penpot/penpot repository

## Workflow

### 1. Determine the target version

The version is typically a semver string like `2.15.3`. Confirm with the user
if not specified.

### 2. Fetch all issues and PRs in the milestone

Find the milestone number:

```bash
gh api repos/penpot/penpot/milestones --paginate \
  --jq '.[] | select(.title=="<VERSION>") | {number: .number, title: .title, open_issues: .open_issues, closed_issues: .closed_issues}'
```

Then fetch all items:

```bash
MILESTONE_NUMBER=<NUMBER>
gh api "repos/penpot/penpot/issues?milestone=$MILESTONE_NUMBER&state=all&per_page=100" \
  --jq '.[] | {number: .number, title: .title, state: .state, labels: [.labels[].name], pull_request: .pull_request != null}'
```

### 3. Identify issue ↔ PR relationships

For each item, determine the relationship:

- **Issue** (`pull_request: false`): This is the user-facing issue. It
  becomes the primary link in the changelog.
- **PR** (`pull_request: true`): Check if it has `Fixes #<NUMBER>` in its
  body to find which issue it closes.

To find the linked issue for a PR:

```bash
gh pr view <PR_NUMBER> --repo penpot/penpot \
  --json body,closingIssuesReferences --jq '{closingIssues: [.closingIssuesReferences[].number]}'
```

**Only closed issues are included.** An issue must have `state: "closed"` to
appear in the changelog. Open/unresolved issues are omitted, even if they are
tracked in the milestone.

**Pairing rules:**

| Pattern | Changelog format |
|---------|-----------------|
| Closed issue + one or more PRs fix it | Primary link = issue, sub-line with PRs comma-separated |
| PR exists with no linked issue | If a corresponding closed issue exists in the same milestone, link the issue. Otherwise, skip the entry (the issue must be the changelog unit). |
| Closed issue with no fix PR in milestone | Link the issue directly, without a PR sub-line. |

### 4. Categorize entries

Check the labels on each issue/PR:

```bash
gh issue view <NUMBER> --repo penpot/penpot --json labels --jq '[.labels[].name]'
```

| Label / Title prefix | Changelog section |
|----------------------|-------------------|
| `bug` label or `:bug:` title prefix | `### :bug: Bugs fixed` |
| `enhancement` label or `:sparkles:` prefix | `### :sparkles: New features & Enhancements` |
| No label | Infer from title convention, default to bug fix |

**Community contribution attribution:** If the issue or its fix PR has the
`community contribution` label, add an attribution `(by @<github_username>)`
on the changelog entry line, **before** the GitHub issue/PR references.
Fetch the author:

```bash
gh issue view <NUMBER> --repo penpot/penpot --json author --jq '.author.login'
```

Placement in the entry line:
```markdown
- Fix description of the bug (by @username) [Github #<ISSUE>](...)
  (PR: [#<PR>](...))
```

### 5. Read the current CHANGES.md

Read the top of `CHANGES.md` to understand the existing format and find the
insertion point (newest version goes at the top, after the `# CHANGELOG`
header).

Key format rules from the existing file:

```markdown
## <VERSION>

### :bug: Bugs fixed

- Fix description of the bug [Github #<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>)
  (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
- Fix another bug (by @contributor) [Github #<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>)
  (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))

### :sparkles: New features & Enhancements

- Add new feature description [Github #<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>)
  (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
```

Format details:
- Entries start with `- ` followed by a short description in imperative mood
- Primary link is **always the issue** (user-facing artifact)
- PR references are on an indented sub-line: `  (PR: [#<N>](<url>))`
  If an issue has multiple fix PRs, they are comma-separated on one line:
  `  (PR: [#<N>](<url>), [#<M>](<url>))`
- The description should describe the fix/feature from the user's perspective
- Community contributions get `(by @<username>)` **before** the GitHub link
- Sections are separated by a blank line between the last entry and the next
  section title
- Only include a section if there are entries for it

### 6. Build the description text

Derive the description from the issue title, not the PR title. Strip leading
emoji prefixes (`:bug:`, `:sparkles:`, `:tada:`) and focus on the
user-facing behavior.

Examples:

| Issue title | Changelog description |
|-------------|----------------------|
| `Plugin API token methods fail with schema validation error on PRO` | `Fix Plugin API token methods failing with schema validation error on PRO` |
| `Comment content is not sanitized before rendering, enabling stored XSS` | `Sanitize comment content on rendering` |
| `Custom uploaded font family names are not sanitized` | `Sanitize font family names on custom uploaded fonts` |

### 7. Insert the section into CHANGES.md

Insert the new version section right after the `# CHANGELOG` header (before
the previous version entry). Use the `edit` tool with enough context to make
a unique match.

### 8. Verify

Read the top of `CHANGES.md` and confirm:
- The version header is correct
- Every entry has a GitHub link
- Entries with a fix PR have the PR sub-line
- The section ordering is correct (newest first)
- Formatting matches the surrounding entries

## Version section template

```markdown
## <VERSION>

### :bug: Bugs fixed

- <fix description> [Github #<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>)
  (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
- <fix description> (by @contributor) [Github #<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>)
  (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
```

## Key Principles

- **Issue = changelog unit.** The primary link always points to the
  user-facing issue, not the implementation PR.
- **PR = implementation detail.** Reference the PR on a sub-line so readers
  can find the code changes.
- **Latest version first.** New sections are inserted at the top of the
  changelog, below the `# CHANGELOG` header.
- **User-facing descriptions.** Write from the user's perspective — describe
  what broke and what was fixed, not internal implementation details.
- **Community attribution.** When the issue or fix PR has the
  `community contribution` label, add `(by @<username>)` on the entry line
  between the description and the GitHub link.
- **Only closed issues.** An issue must have `state: "closed"` to appear in
  the changelog. Open unresolved issues are omitted.
- **Multiple PRs per issue.** If multiple PRs fix the same issue, list them
  comma-separated on the same sub-line: `(PR: [#A](url), [#B](url))`.
- **Re-fetch before editing.** Milestones can change — always re-fetch issues
  before making edits, don't rely on cached data.
