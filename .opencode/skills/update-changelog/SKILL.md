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

**Exclusion rules (issue-level):**
- `no changelog` label — Chore/refactor work that doesn't need a changelog entry
- `release blocker` label — Blocked issues not yet ready for changelog
- `Task` issue type — Internal chores are not user-facing; filter these out after fetching

**Exclusion rules (PR-level):**
In addition to issue-level exclusions, PRs with these labels should be
excluded regardless of their linked issue's labels:
- `release blocker` — PR is part of a pending release blocker batch
- `no issue required` — Trivial fix not tracked as an issue

The script outputs JSON with each entry containing `number`, `title`, `state`,
`issue_type`, `labels`, and `closing_prs` (the PRs that fix each issue).

### 3. Identify missing entries (optional)

If updating from an existing `CHANGES.md`, find issues in the milestone that
are NOT yet referenced in the changelog:

```bash
python3 tools/gh.py issues "2.16.0" --exclude "release blocker,no changelog" --compare CHANGES.md
```

This returns a filtered JSON array with only the missing issues.

> **Note:** The `--compare` flag checks **issues** only (via issue number
> references in the changelog). To find merged **PRs** not yet referenced,
> use the milestone PR cross-reference described in step 10 below.

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

The `prs` command also supports listing all PRs in a milestone in one call:

```bash
# All merged PRs in a milestone (default)
python3 tools/gh.py prs --milestone "2.16.0"

# All states (merged, open, closed)
python3 tools/gh.py prs --milestone "2.16.0" --state all
```

The `prs` command returns JSON with `number`, `title`, `body`, `state`,
`merged_at`, `milestone`, `author`, `labels`, and `closing_issues`. PRs are
fetched in batches of 50 via GraphQL to stay within API limits (milestone mode
uses paginated GraphQL on the milestone's `pullRequests` connection).

> **⚠️ CRITICAL: Never iterate PRs one-by-one with `for pr in ...; do gh pr view ...; done`.**
> This causes N+1 API calls and will quickly exhaust GitHub's rate limit.
> **Always use `tools/gh.py prs <N1> <N2> ...`** which batches up to 50 PRs
> per GraphQL query. If a field you need is missing from `gh.py`'s output,
> **add it to the script** (edit the `GQL_PRS_QUERY_ITEM` template and the
> result builder in `fetch_prs_batch`) rather than working around it with
> one-by-one calls.

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

> **False-positive associations:** A PR may incorrectly claim to close an issue
> from a different context (e.g., a very old PR referencing a modern issue, or a
> cross-project reference). If the PR title and issue title are clearly unrelated,
> or the PR was created years before the issue, treat it as a data glitch and
> skip it. PR [#3](https://github.com/penpot/penpot/pull/3) (ancient License PR
> claiming to close a plugin API issue) is a known example.

### 5a. ⚠️ Verify PR merge status before writing

A closed issue may list closing PRs that were **closed without merging**
(e.g., a community PR that was superseded by another). The changelog must
only reference **merged** PRs. Verify before writing:

```bash
# Collect all PR numbers from the candidate entries and check them
python3 tools/gh.py prs <ALL_PR_NUMBERS> | python3 -c "
import json, sys
for pr in json.load(sys.stdin):
    if pr['state'] != 'MERGED':
        print(f'WARNING: #{pr[\"number\"]} is {pr[\"state\"]} (not merged)')
"
```

If a closing PR is closed-unmerged, find the actual merged PR that
superseded it:
1. Check the issue's closing PRs list for other PRs (there may be multiple)
2. Look for other PRs with similar titles or descriptions referencing the same issue
3. Inspect the closed PR's conversation timeline for a pointer to the replacement

Replace the reference in the changelog entry with the correct merged PR number.

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

### 10. Cross-reference milestone PRs against the changelog

Issues can be fixed by PRs that aren't in the milestone, and merged PRs in
the milestone may not close any tracked issue. After writing, run a full
cross-reference to catch gaps:

```bash
# List all merged PRs in the milestone
python3 tools/gh.py prs --milestone "<MILESTONE>" --state merged > /tmp/milestone-prs.json

# Extract PR numbers from the changelog section
python3 -c "
import json, re

with open('CHANGES.md') as f:
    content = f.read()

# Extract the version section (adjust regex to match the actual version)
match = re.search(r'## <MILESTONE> \(Unreleased\)\n(.*?)(?:\n## |\Z)', content, re.DOTALL)
section = match.group(1)

# Collect all PR numbers referenced
changelog_prs = set()
for m in re.findall(r'\[#(\d+)\]\(https://github\.com/penpot/penpot/pull/\d+\)', section):
    changelog_prs.add(int(m))

# Collect all milestone PRs (filtered)
with open('/tmp/milestone-prs.json') as f:
    milestone_prs = json.load(f)

milestone_merged = {pr['number'] for pr in milestone_prs}

# PRs in milestone but not in changelog
missing = sorted(milestone_merged - changelog_prs)
print(f'Milestone merged PRs: {len(milestone_merged)}')
print(f'Changelog referenced PRs: {len(changelog_prs)}')
print(f'PRs in milestone but NOT in changelog: {len(missing)}')
for num in missing:
    pr = next(p for p in milestone_prs if p['number'] == num)
    print(f'  #{num} {pr[\"title\"][:80]}')
"
```

For each missing PR found, decide whether it should be added to the
changelog or is legitimately excluded (check its labels).

Also verify that no closed-unmerged PRs remain in the changelog:

```bash
python3 tools/gh.py prs --milestone "<MILESTONE>" --state all | python3 -c "
import json, sys
data = json.load(sys.stdin)
closed = [p for p in data if p['state'] == 'CLOSED']
if closed:
    print('WARNING: CLOSED (unmerged) PRs in milestone:')
    for p in closed:
        print(f'  #{p[\"number\"]} {p[\"title\"][:80]}')
"
```

**Post-edit audit checklist:**
- ✅ All referenced PRs are merged (no closed-unmerged artifacts)
- ✅ Every merged milestone PR is either in the changelog or excluded by label
- ✅ PR and issue counts are internally consistent
- ✅ No false-positive PR-to-issue associations

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
- **Verify PR merge status.** Not all closing PRs are merged — community PRs
  can be superseded and closed without merging. Always check that every PR
  referenced in the changelog has `state: MERGED`.
- **PR-level exclusions apply.** A PR can carry its own exclusion labels
  (`release blocker`, `no issue required`) independent of its linked issue's
  labels. Check both.
- **Cross-reference milestone PRs, not just issues.** The `--compare` flag on
  the `issues` command only compares issue numbers. Merged PRs not linked to
  any milestone issue can be missed. Use `python3 tools/gh.py prs --milestone`
  for a full PR cross-reference.
- **False-positive PR-to-issue associations.** A PR may claim to close an
  issue from a different project or context. If the PR title and issue title
  are clearly unrelated, or the PR predates the issue by years, treat it as a
  data glitch and skip it.
