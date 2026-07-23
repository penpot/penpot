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
- `scripts/gh.py` helper script available

## Workflow

### 1. Determine the target version

The version is typically a semver string like `2.15.3`. Confirm with the user
if not specified.

### 2. Fetch all issues in the milestone

Use the helper script. It uses GraphQL for efficient single-pass fetching
(closing PRs are included in the same query — no N+1):

```bash
# All closed issues (default)
python3 scripts/gh.py issues "2.16.0"

# Include open issues too
python3 scripts/gh.py issues "2.16.0" --state all

# Exclude entries that should not go in the changelog
python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog"
```

**Exclusion rules (issue-level):**
- `no changelog` label — Chore/refactor work that doesn't need a changelog entry
- `release blocker` label — Blocked issues not yet ready for changelog
- `Task` issue type — Internal chores are not user-facing; automatically excluded by `gh.py`. Use `--include-tasks` to override.
- **Rejected project status** — Issues with a "Rejected" status in the "Main" project board are automatically excluded by `gh.py`. This project-level status (independent of the GitHub issue `state`) indicates the issue was rejected from the release. Use `--include-rejected` to override.

**Exclusion rules (PR-level):**
In addition to issue-level exclusions, PRs with these labels should be
excluded regardless of their linked issue's labels:
- `release blocker` — PR is part of a pending release blocker batch
- `no issue required` — Trivial fix not tracked as an issue

The script outputs JSON with each entry containing `number`, `title`, `state`,
`issue_type`, `labels`, `closing_prs` (the PRs that fix each issue), and
`project_status` (the "Main" project board status, e.g. "Done", "Rejected",
or `null` if not tracked in a project).

### 3. Identify missing entries (optional)

If updating from an existing `CHANGES.md`, find issues in the milestone that
are NOT yet referenced in the changelog:

```bash
python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog" --compare CHANGES.md
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
python3 scripts/gh.py prs 9179 9204 9311

# From a file
python3 scripts/gh.py prs --file prs.txt

# From stdin
cat prs.txt | python3 scripts/gh.py prs --stdin
```

The `prs` command also supports listing all PRs in a milestone in one call:

```bash
# All merged PRs in a milestone (default)
python3 scripts/gh.py prs --milestone "2.16.0"

# All states (merged, open, closed)
python3 scripts/gh.py prs --milestone "2.16.0" --state all
```

The `prs` command returns JSON with `number`, `title`, `body`, `state`,
`merged_at`, `author`, `labels`, and `closing_issues`. PRs are fetched in
batches of 50 via GraphQL to stay within API limits (milestone mode uses
paginated GraphQL on the milestone's `pullRequests` connection).

You can also list all PRs in a milestone in a single call:

```bash
# All merged PRs in a milestone (default)
python3 scripts/gh.py prs --milestone "2.16.0"

# All states (merged, open, closed)
python3 scripts/gh.py prs --milestone "2.16.0" --state all

# Open PRs only
python3 scripts/gh.py prs --milestone "2.16.0" --state open
```

The milestone path uses paginated GraphQL on the milestone's `pullRequests`
connection (100 per page), avoiding one-by-one fetches.

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

**Preserve highlighted entries:** If an entry is already featured in
`### :rocket: Epics and highlights`, keep it in that section when refreshing a
changelog version. Do not remove a highlighted entry just because issue type
categorization would otherwise place it under `### :sparkles: New features &
Enhancements`.

**Community contribution attribution:** If the issue or its fix PR has the
`community contribution` label, add an attribution `(by @<github_username>)`
on the changelog entry line, **before** the GitHub issue/PR references.

The attribution should reference the **PR author**, not the issue author.
The `prs` subcommand includes the `author` field — use that:

```bash
python3 scripts/gh.py prs <PR_NUMBER> | python3 -c "import sys,json; print(json.load(sys.stdin)[0]['author'])"
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
python3 scripts/gh.py prs <ALL_PR_NUMBERS> | python3 -c "
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

### 6a. Pre-flight checks — fix rule violations in the changelog

**The LLM must apply these checks during the workflow and fix any
violations directly in `CHANGES.md`. They are not anomalies — they are
process errors that should be corrected before writing the new section.**

The changelog is a *snapshot* of the milestone at a point in time, but
milestones and changelog entries can drift. The LLM must reconcile the
existing changelog against the current state of the milestone and the
existing changelog entries.

For each entry that already exists in `CHANGES.md` (in any version
section) or in the candidate set for the current milestone, check:

1. **Duplicate across versions.** Is the same issue already documented
   in another (older) version section? If yes, this is a *backport*:
   - The user-facing fix was already released. Remove the duplicate
     from the current section. The earlier version is the canonical
     reference.

2. **Stale milestone assignment.** Has the issue been moved out of the
   current milestone since the changelog was last updated (e.g., a fix
   arrived late and the issue was reassigned to a future milestone)?
   - Verify the issue is still in the current milestone via
     `python3 scripts/gh.py issues <MILESTONE> --state all`. If it's no
     longer there, remove the entry from the current section. (If the
     target section doesn't exist yet, the entry is simply dropped.)

3. **Exclusion labels newly applied.** Did the issue acquire a
   `no changelog` or `release blocker` label since the changelog was
   last updated? If yes, remove the entry from the current section.

4. **Issue state changed.** Is the issue still closed? Has it been
   reopened, deleted, or moved to a `Rejected` project status? If yes,
   remove the entry.

5. **Unmerged or removed PR references.** For every PR referenced in
   the entry, is the PR still merged? Was the PR closed without
   merging (superseded)? Was the PR moved to a different milestone?
   If the only referenced PR is no longer merged, fix the reference
   (find the actual merged fix PR) or remove the entry. A PR that is
   merged in a *different* milestone is reported as an anomaly in
   step 11 — do not silently remove it.

6. **Issue type changed.** Did the issue type change (e.g., from Bug to
   Task)? If the new type is `Task`, the issue is internal and should
   be removed.

7. **Cross-section completeness.** For every closed, non-excluded
   milestone issue that is *not* referenced in any version section of
   the changelog, add it to the current section (per the categorization
   rules in step 5).

After these checks, the changelog should be internally consistent with
the milestone. **Do not defer these fixes to step 11 — they are
workflow errors, not anomalies.** Step 11 only reports milestone
mismatches that require human judgment about the team's release
intent.

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
python3 scripts/gh.py prs --milestone "<MILESTONE>" --state merged > /tmp/milestone-prs.json

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
python3 scripts/gh.py prs --milestone "<MILESTONE>" --state all | python3 -c "
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

### 11. Generate anomaly report and save to CHANGES-ISSUES.md

After all edits and cross-referencing are complete, generate a structured
report and save it to `CHANGES-ISSUES.md` (overwriting if exists).
This provides a persistent record of any discrepancies between the milestone
and the changelog.

**Every issue and PR number in the report must be rendered as a full GitHub
Markdown link** using the same URL format as `CHANGES.md`:
- Issue N → `[#N](https://github.com/penpot/penpot/issues/N)`
- PR N → `[#N](https://github.com/penpot/penpot/pull/N)`

The titles and notes should also link to the corresponding issue/PR page
where applicable, so the report is self-contained and clickable from any
Markdown viewer.

## What is an anomaly

**An anomaly is a milestone-mismatch between an issue and its referenced
PR.** It indicates that the changelog claim "this issue is fixed by this PR,
all in milestone M" is inconsistent with the actual milestone assignments.
There are exactly two types:

1. **Issue is in the milestone, but its referenced PR is in a different
   milestone (or has no milestone).** The changelog claims a fix in this
   release, but the PR is being released elsewhere — the fix may not
   actually ship here.
2. **PR is in the milestone, but the issue it closes is in a different
   milestone (or has no milestone).** The PR is being released here, but
   the issue it fixes is being released in a different version (or never
   tracked in a milestone) — the changelog pairing is misleading.

**Anything else is not an anomaly.** Other discrepancies (exclusion
labels on in-changelog issues, missing valid issues, unmerged PR
references, duplicates across versions, stale milestone assignments)
are **rule violations** that the LLM must fix directly in `CHANGES.md`
during step 6a (pre-flight checks). They should not appear in this
report — if they do, the LLM has skipped the pre-flight step and
needs to re-run the workflow.

The changelog's primary unit is the **issue**, not the PR, so a missing or
mismatched PR only matters when its issue is part of this milestone.

Run this self-contained script:

```bash
python3 << 'PYEOF'
import json, re, subprocess, sys
from datetime import datetime, timezone

MILESTONE = "<MILESTONE>"
CHANGES_MD = "CHANGES.md"
OUTPUT = "CHANGES-ISSUES.md"
REPO = "penpot/penpot"

# --- URL helpers (match CHANGES.md format exactly) ---
def issue_url(n):  return f"https://github.com/{REPO}/issues/{n}"
def pr_url(n):     return f"https://github.com/{REPO}/pull/{n}"
def issue_link(n): return f"[#{n}]({issue_url(n)})"
def pr_link(n):    return f"[#{n}]({pr_url(n)})"
def issue_link_title(n, title):
    url = issue_url(n)
    if title:
        return f"[#{n}]({url}) — [{title}]({url})"
    return f"[#{n}]({url})"
def pr_link_title(n, title):
    url = pr_url(n)
    if title:
        return f"[#{n}]({url}) — [{title}]({url})"
    return f"[#{n}]({url})"
def fmt_pr_list(nums):
    return ", ".join(pr_link(n) for n in nums)
def fmt_issue_list(nums):
    return ", ".join(issue_link(n) for n in nums)

# --- Fetch milestone data ---
result = subprocess.run(
    ["python3", "scripts/gh.py", "issues", MILESTONE, "--state", "all"],
    capture_output=True, text=True)
all_issues = json.loads(result.stdout)
issue_by_num = {i['number']: i for i in all_issues}

result = subprocess.run(
    ["python3", "scripts/gh.py", "prs", "--milestone", MILESTONE, "--state", "all"],
    capture_output=True, text=True)
all_prs = json.loads(result.stdout)
pr_by_num = {p['number']: p for p in all_prs}

# --- Read changelog section ---
with open(CHANGES_MD) as f:
    content = f.read()

m = re.search(rf'## {re.escape(MILESTONE)}(?:\s*\([^)]*\))?\n(.*?)(?:\n## |\Z)', content, re.DOTALL)
section = m.group(1) if m else ""

changelog_issues = set()
for num in re.findall(r'\[#(\d+)\]\(https://github\.com/penpot/penpot/issues/\d+\)', section):
    changelog_issues.add(int(num))
for num in re.findall(r'\[Github #(\d+)\]', section):
    changelog_issues.add(int(num))

changelog_prs = set()
for num in re.findall(r'\[#(\d+)\]\(https://github\.com/penpot/penpot/pull/\d+\)', section):
    changelog_prs.add(int(num))
for num in re.findall(r'PR:\[(\d+)\]', section):
    changelog_prs.add(int(num))

# --- Milestone lookup caches ---
# PRs and issues returned by milestone queries are KNOWN to be in MILESTONE.
# For everything else, fall back to `gh` per-item lookups.
pr_milestone_cache = {p['number']: MILESTONE for p in all_prs}
issue_milestone_cache = {i['number']: MILESTONE for i in all_issues}

def get_pr_milestone(pr_num):
    """Return the milestone title for a PR, or None if unassigned / unknown."""
    if pr_num in pr_milestone_cache:
        return pr_milestone_cache[pr_num]
    try:
        r = subprocess.run(
            ["gh", "pr", "view", str(pr_num), "--json", "milestone"],
            capture_output=True, text=True, check=True)
        data = json.loads(r.stdout)
        ms = data.get('milestone')
        pr_milestone_cache[pr_num] = (ms or {}).get('title')
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        pr_milestone_cache[pr_num] = None
    return pr_milestone_cache[pr_num]

def get_issue_milestone(issue_num):
    """Return the milestone title for an issue, or None if unassigned / unknown."""
    if issue_num in issue_milestone_cache:
        return issue_milestone_cache[issue_num]
    try:
        r = subprocess.run(
            ["gh", "issue", "view", str(issue_num), "--json", "milestone"],
            capture_output=True, text=True, check=True)
        data = json.loads(r.stdout)
        ms = data.get('milestone')
        issue_milestone_cache[issue_num] = (ms or {}).get('title')
    except (subprocess.CalledProcessError, json.JSONDecodeError):
        issue_milestone_cache[issue_num] = None
    return issue_milestone_cache[issue_num]

# --- Exclusion rules (shared) ---
EXCLUDED_LABELS = {'release blocker', 'no changelog'}
EXCLUDED_ISSUE_TYPES = {'Task'}
EXCLUDED_PROJECT_STATUS = {'Rejected'}

def issue_excluded(issue):
    if not issue: return True
    if issue.get('state') != 'CLOSED': return True
    if issue.get('issue_type') in EXCLUDED_ISSUE_TYPES: return True
    if issue.get('project_status') in EXCLUDED_PROJECT_STATUS: return True
    if EXCLUDED_LABELS & set(issue.get('labels', [])): return True
    return False

# --- ANOMALIES: milestone mismatches between issues and their referenced PRs ---
# These are the ONLY items that should appear in the report. All other
# discrepancies (exclusion labels, missing valid issues, unmerged PRs,
# duplicates, stale milestone assignments) are workflow errors that the
# LLM must fix in step 6a (pre-flight checks) — they are not anomalies.

# Type A: issue in MILESTONE, referenced PR in different milestone or no milestone
anomalies_a = []  # list of dicts: {issue, issue_title, pr, pr_milestone}
for issue_num in sorted(changelog_issues):
    issue = issue_by_num.get(issue_num)
    if not issue: continue
    if get_issue_milestone(issue_num) != MILESTONE: continue
    for pr_num in issue.get('closing_prs', []):
        pr_ms = get_pr_milestone(pr_num)
        if pr_ms != MILESTONE:
            anomalies_a.append({
                'issue': issue_num,
                'issue_title': issue.get('title', ''),
                'pr': pr_num,
                'pr_milestone': pr_ms,  # may be None
            })

# Type B: PR in MILESTONE, the issue it closes is in different milestone or no milestone
anomalies_b = []  # list of dicts: {pr, pr_title, issue, issue_milestone}
for pr_num in sorted(changelog_prs):
    pr = pr_by_num.get(pr_num)
    if not pr: continue
    if get_pr_milestone(pr_num) != MILESTONE: continue
    for issue_num in pr.get('closing_issues', []):
        issue_ms = get_issue_milestone(issue_num)
        if issue_ms != MILESTONE:
            anomalies_b.append({
                'pr': pr_num,
                'pr_title': pr.get('title', ''),
                'issue': issue_num,
                'issue_milestone': issue_ms,  # may be None
            })

# --- Write report ---
def fmt_ms(ms):
    return ms if ms else "_none_"

with open(OUTPUT, 'w') as f:
    f.write(f'# Changelog Anomaly Report — {MILESTONE}\n\n')
    f.write(f'Generated: {datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")}\n\n')
    f.write('---\n\n')

    n_a = len(anomalies_a)
    n_b = len(anomalies_b)

    f.write('## Summary\n\n')
    f.write(f'- **Issue in {MILESTONE}, referenced PR in different milestone or no milestone:** {n_a}\n')
    f.write(f'- **PR in {MILESTONE}, closing issue in different milestone or no milestone:** {n_b}\n')
    f.write(f'- **Total anomalies:** {n_a + n_b}\n\n')

    # --- Anomalies section ---
    if n_a or n_b:
        f.write('## Anomalies\n\n')
        f.write('These are milestone mismatches between an issue in the changelog '
                'and its referenced PR (or vice-versa). The changelog claim '
                '"this issue is fixed by this PR, all in this milestone" is '
                'inconsistent with the actual milestone assignments. '
                'Resolve by either updating the milestone on the issue/PR or '
                'removing the misleading entry from the changelog.\n\n')

        if n_a:
            f.write(f'### Issue in {MILESTONE}, PR in different milestone or no milestone\n\n')
            by_issue = {}
            for a in anomalies_a:
                by_issue.setdefault(a['issue'], []).append(a)
            for issue_num in sorted(by_issue):
                entries = by_issue[issue_num]
                title = entries[0]['issue_title']
                f.write(f'- {issue_link_title(issue_num, title[:80])}\n')
                for e in entries:
                    ms_label = fmt_ms(e['pr_milestone'])
                    badge = '🔴' if e['pr_milestone'] is None else '⚠️'
                    f.write(f'  - {badge} Referenced {pr_link(e["pr"])} is in milestone **{ms_label}** (expected: {MILESTONE})\n')
                f.write('\n')

        if n_b:
            f.write(f'\n### PR in {MILESTONE}, closing issue in different milestone or no milestone\n\n')
            by_pr = {}
            for b in anomalies_b:
                by_pr.setdefault(b['pr'], []).append(b)
            for pr_num in sorted(by_pr):
                entries = by_pr[pr_num]
                title = entries[0]['pr_title']
                f.write(f'- {pr_link_title(pr_num, title[:80])}\n')
                for e in entries:
                    ms_label = fmt_ms(e['issue_milestone'])
                    badge = '🔴' if e['issue_milestone'] is None else '⚠️'
                    f.write(f'  - {badge} Closing {issue_link(e["issue"])} is in milestone **{ms_label}** (expected: {MILESTONE})\n')
                f.write('\n')
    else:
        f.write('✅ No anomalies found. All (issue, PR) pairs in the changelog have aligned milestone assignments.\n\n')

    # --- Context ---
    f.write('---\n\n')
    f.write('## Context\n\n')
    f.write(f'- Milestone: **{MILESTONE}**\n')
    f.write(f'- Milestone total issues (all states): {len(all_issues)}\n')
    f.write(f'- Closed issues in milestone: {sum(1 for i in all_issues if i.get("state") == "CLOSED")}\n')
    f.write(f'- Valid issues after exclusions (after step 5/6a): {len([i for i in all_issues if not issue_excluded(i)])}\n')
    f.write(f'- Issues referenced in changelog: {len(changelog_issues)}\n')
    f.write(f'- PRs referenced in changelog: {len(changelog_prs)}\n')

print(f"Anomaly report written to {OUTPUT}")
PYEOF
```

This generates `CHANGES-ISSUES.md` containing **only the anomalies** —
milestone mismatches between issues and their referenced PRs:

1. **Issue in milestone, referenced PR in different milestone or no milestone** —
   the changelog claims a fix here, but the PR is released elsewhere.
2. **PR in milestone, closing issue in different milestone or no milestone** —
   the PR is released here, but the issue it fixes belongs to another version.

**Rule violations are not in the report** — they are workflow errors the
LLM must fix directly in `CHANGES.md` during step 6a (pre-flight checks).
If the report contains a rule violation, the LLM has skipped the pre-flight
step and needs to re-run the workflow before re-generating the report.

The report is overwritten each time it's generated, reflecting the current
state of the milestone and changelog. Every number is rendered as a full
`[#N](https://github.com/penpot/penpot/issues/N)` or
`[#N](https://github.com/penpot/penpot/pull/N)` link so the report is
self-contained and clickable in any Markdown viewer.

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
  the changelog. Open/unresolved issues are omitted.
- **Rejected project status.** Issues marked as "Rejected" in the "Main"
  project board are automatically excluded by `gh.py`, even if they are
  closed. The project status is distinct from the GitHub issue state.
  Use `--include-rejected` to override this behavior.
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
- **Use `scripts/gh.py`.** Prefer the helper script over raw `gh api` calls for
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
  any milestone issue can be missed. Use `python3 scripts/gh.py prs --milestone`
  for a full PR cross-reference.
- **False-positive PR-to-issue associations.** A PR may claim to close an
  issue from a different project or context. If the PR title and issue title
  are clearly unrelated, or the PR predates the issue by years, treat it as a
  data glitch and skip it.
- **Anomaly = milestone mismatch only.** The report contains only milestone
  mismatches: (1) the issue is in this milestone but the referenced PR is
  in a different milestone (or unassigned), and (2) the PR is in this
  milestone but the issue it closes is in a different milestone (or
  unassigned). These are anomalies because the changelog pairing is
  *misleading* — the human needs to decide whether the milestone or the
  changelog is wrong. All other discrepancies (exclusion labels, missing
  valid issues, unmerged PR references, duplicates, stale milestone
  assignments) are **rule violations** that the LLM must fix directly in
  `CHANGES.md` during step 6a (pre-flight checks). They never appear in
  the report — if they do, the pre-flight step was skipped.
