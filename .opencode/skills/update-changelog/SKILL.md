---
name: update-changelog
description: Update the project CHANGES.md with issues from a given GitHub milestone, with correct categorization and references.
---

# Skill: update-changelog

Update `CHANGES.md` with entries for all issues and PRs in a given GitHub
milestone. Each entry references the user-facing issue (not the PR) as the
primary link, with the fix PR inline on the same line.

## When to Use

- Before a new release, to populate the changelog with all fixed issues
- When new issues are added to an existing milestone and the changelog needs
  to be refreshed
- To ensure every entry follows the correct format for the changelog

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)
- Python 3.8+
- `tools/gh.py` helper script available

## Workflow

### 1. Determine the target version

The version is typically a semver string like `2.15.3`. Confirm with the user
if not specified.

### 2. Fetch all issues in the milestone

Use the helper script. It uses GraphQL for efficient single-pass fetching
(closing PRs are included in the same query — no N+1):

```bash
# All closed issues (default)
python3 tools/gh.py issues "2.16.0"

# Include open issues too
python3 tools/gh.py issues "2.16.0" --state all

# Exclude entries that should not go in the changelog
python3 tools/gh.py issues "2.16.0" --exclude "release blocker,no changelog"
```

**Exclusion rules:**
- `no changelog` label — Chore/refactor work that doesn't need a changelog entry
- `Task` issue type — Internal chores are not user-facing; filter these out after fetching

The script outputs JSON with each entry containing `number`, `title`, `state`,
`issue_type`, `labels`, and `closing_prs` (the PRs that fix each issue).

### 3. Identify missing entries (optional)

If updating from an existing `CHANGES.md`, find issues in the milestone that
are NOT yet referenced in the changelog:

```bash
python3 tools/gh.py issues "2.16.0" --exclude "release blocker,no changelog" --compare CHANGES.md
```

This returns a filtered JSON array with only the missing issues.

### 4. Fetch additional PR details when needed

When you need more context for specific PRs (e.g. to find the PR author for
community contribution attribution, or to read the PR body for
"Fixes/Closes #NNN" patterns):

```bash
# One or more PR numbers
python3 tools/gh.py prs 9179 9204 9311

# From a file
python3 tools/gh.py prs --file prs.txt

# From stdin
cat prs.txt | python3 tools/gh.py prs --stdin
```

The `prs` command returns JSON with `number`, `title`, `body`, `state`,
`merged_at`, `author`, `labels`, and `closing_issues`. PRs are fetched in
batches of 50 via GraphQL to stay within API limits.

### 5. Categorize entries — strictly by issue type, never by labels or emoji

Use the **Issue Type** field (GitHub's native issue type, exposed as
`issue_type` in the `gh.py` JSON output) to determine which section an entry
belongs to.

> **⚠️ CRITICAL: Never use labels or title emoji prefixes for categorization.**
> Labels like `bug` and `enhancement`, as well as title prefixes like `:bug:`
> and `:sparkles:`, are frequently inaccurate, missing, or contradictory to the
> actual issue type. The `issue_type` field from `gh.py` is the single source
> of truth.

| `issue_type` value | Changelog section |
|--------------------|-------------------|
| `Bug` | `### :bug: Bugs fixed` |
| `Feature` or `Enhancement` | `### :sparkles: New features & Enhancements` |
| `Task` | **Exclude** — internal chores are not user-facing |
| `null` (not set) | Check labels as a fallback: `bug` label → bugs, otherwise enhancements |

The `gh.py` issues command already includes `issue_type` in every entry's
output. **No separate GraphQL query is needed.**

**Community contribution attribution:** If the issue or its fix PR has the
`community contribution` label, add an attribution `(by @<github_username>)`
on the changelog entry line, **before** the GitHub issue/PR references.

The attribution should reference the **PR author**, not the issue author.
The `prs` subcommand includes the `author` field — use that:

```bash
python3 tools/gh.py prs <PR_NUMBER> | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['author'])"
```

Placement in the entry line:
```markdown
- Fix description of the bug (by @username) [#<ISSUE>](...) (PR: [#<PR>](...))
```

**Only closed issues are included.** An issue must have `state: "closed"` to
appear in the changelog. Open/unresolved issues are omitted, even if they are
tracked in the milestone.

**Pairing rules:**

| Pattern | Changelog format |
|---------|-----------------|
| Closed issue + one or more PRs fix it | Primary link = issue, PR inline comma-separated |
| PR exists with no linked issue | If a corresponding closed issue exists in the same milestone, link the issue. Otherwise, skip the entry (the issue must be the changelog unit). |
| Closed issue with no fix PR in milestone | Link the issue directly, without a PR reference. |

### 6. Read the current CHANGES.md

Read the top of `CHANGES.md` to understand the existing format and find the
insertion point (newest version goes at the top, after the `# CHANGELOG`
header).

Key format rules from the existing file:

```markdown
## <VERSION>

### :bug: Bugs fixed

- Fix description of the bug [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
- Fix another bug (by @contributor) [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))

### :sparkles: New features & Enhancements

- Add new feature description [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
```

Format details:
- Entries start with `- ` followed by a short description in imperative mood
- Primary link is **always the issue** (user-facing artifact)
- PR references are inline on the same line: `(PR: [#<N>](<url>))`
  If an issue has multiple fix PRs, they are comma-separated:
  `(PR: [#<N>](<url>), [#<M>](<url>))`
- The description should describe the fix/feature from the user's perspective
- Community contributions get `(by @<username>)` **before** the issue link
- Sections are separated by a blank line between the last entry and the next
  section title
- Only include a section if there are entries for it
- When an entry already exists in an earlier version section, it must be removed
  from the current version to avoid duplicates

### 7. Build the description text

Derive the description from the issue title, not the PR title. Strip leading
emoji prefixes (`:bug:`, `:sparkles:`, `:tada:`) and focus on the
user-facing behavior.

Examples:

| Issue title | Changelog description |
|-------------|----------------------|
| `Plugin API token methods fail with schema validation error on PRO` | `Fix Plugin API token methods failing with schema validation error on PRO` |
| `Comment content is not sanitized before rendering, enabling stored XSS` | `Sanitize comment content on rendering` |
| `Custom uploaded font family names are not sanitized` | `Sanitize font family names on custom uploaded fonts` |

### 8. Insert the section into CHANGES.md

Insert the new version section right after the `# CHANGELOG` header (before
the previous version entry). Use the `edit` tool with enough context to make
a unique match.

### 9. Verify

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

- <fix description> [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
- <fix description> (by @contributor) [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
```

## Key Principles

- **Issue = changelog unit.** The primary link always points to the
  user-facing issue, not the implementation PR.
- **PR = implementation detail.** Reference the PR inline so readers
  can find the code changes.
- **Latest version first.** New sections are inserted at the top of the
  changelog, below the `# CHANGELOG` header.
- **Issue Type determines section — exclusively.** Use the `issue_type` field from `gh.py` output (Bug → `:bug:`, Feature/Enhancement → `:sparkles:`). **Do not** use labels (`bug`, `enhancement`) or title emoji prefixes (`:bug:`, `:sparkles:`) — they are frequently wrong or contradictory. The `issue_type` is the single source of truth.
- **User-facing descriptions.** Write from the user's perspective — describe
  what broke and what was fixed, not internal implementation details.
- **Community attribution.** When the issue or fix PR has the
  `community contribution` label, add `(by @<username>)` on the entry line
  between the description and the issue link. Use the **PR author** (not the
  issue author) for the attribution.
- **Only closed issues.** An issue must have `state: "closed"` to appear in
  the changelog. Open unresolved issues are omitted.
- **Excluded issues.** Issues with `no changelog` label must be excluded.
  Issues with `issue_type: "Task"` must also be excluded — they are internal
  chores, not user-facing changes.
- **Multiple PRs per issue.** If multiple PRs fix the same issue, list them
  comma-separated inline: `(PR: [#A](url), [#B](url))`.
- **Duplicate removal.** If an entry already exists in a prior version section,
  remove it from the current version. Check for text-level duplicates (after
  stripping links and attributions) across version sections.
- **Taiga references.** If a changelog entry references a Taiga URL
  (`tree.taiga.io`), attempt to find a corresponding GitHub issue via the
  Taiga description text or by searching GitHub PRs that reference the Taiga
  URL. Replace the Taiga reference with the GitHub issue link and add the PR
  reference if applicable.
- **Re-fetch before editing.** Milestones can change — always re-fetch issues
  before making edits, don't rely on cached data.
- **Use `tools/gh.py`.** Prefer the helper script over raw `gh api` calls for
  milestone issue listing and PR detail fetching. It handles GraphQL
  pagination, batching, and label filtering automatically.
