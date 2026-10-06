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
- `scripts/gh.py` for GitHub queries, `scripts/changelog.py` for changelog
  checks (run `python3 scripts/changelog.py <command> --help` for usage)

## Workflow

### 1. Determine the target version

The version is typically a semver string like `2.15.3`. Confirm with the user
if not specified.

### 2. Fetch all issues in the milestone

```bash
# All closed issues (default)
python3 scripts/gh.py issues "2.16.0"

# Include open issues too
python3 scripts/gh.py issues "2.16.0" --state all

# Exclude entries that should not go in the changelog
python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog"
```

**Exclusion rules (issue-level):**
- `no changelog` label — chore/refactor work, no entry needed
- `release blocker` label — blocked issues not yet ready for changelog
- `Task` issue type — internal chores, not user-facing; excluded by `gh.py`
  (use `--include-tasks` to override)
- **Rejected project status** — issues with "Rejected" status on the "Main"
  project board are excluded by `gh.py` (use `--include-rejected` to override).
  This status is independent of the GitHub issue `state`.

**Exclusion rules (PR-level):** PRs with these labels stay out regardless of
their linked issue's labels: `release blocker`, `no issue required`.

Each entry carries `number`, `title`, `state`, `issue_type`, `labels`,
`closing_prs`, and `project_status`.

### 3. Identify missing entries (optional)

```bash
python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog" --compare CHANGES.md
```

Returns only milestone issues not yet referenced in the changelog. Note: it
compares **issues** only. For unreferenced merged **PRs**, use the
cross-reference in step 8.

### 4. Fetch additional PR details when needed

```bash
# One or more PR numbers (also: --file prs.txt, or --stdin)
python3 scripts/gh.py prs 9179 9204 9311

# All merged PRs in a milestone (default); --state all/open/closed for others
python3 scripts/gh.py prs --milestone "2.16.0"
```

Returns `number`, `title`, `body`, `state`, `merged_at`, `author`, `labels`,
and `closing_issues`. Milestone mode uses paginated GraphQL (100 per page).

### 5. Categorize entries — strictly by issue type, never by labels or emoji

Use the **Issue Type** field (`issue_type` in the `gh.py` output). No separate
query is needed.

> **⚠️ CRITICAL: Never use labels or title emoji prefixes for categorization.**
> Labels like `bug`/`enhancement` and prefixes like `:bug:`/`:sparkles:` are
> often wrong or missing. `issue_type` is the single source of truth.

| `issue_type` value | Changelog section |
|--------------------|-------------------|
| `Bug` | `### :bug: Bugs fixed` |
| `Feature` or `Enhancement` | `### :sparkles: New features & Enhancements` |
| `Task` | **Exclude** — internal chores, not user-facing |
| `null` (not set) | Fallback to labels: `bug` label → bugs, otherwise enhancements |

**Breaking changes override everything:** an issue with the `breaking change`
label goes under `### :boom: Breaking changes & Deprecations`, no matter its
issue type. This is the only label-based rule — it is an explicit exception
to the "never by labels" principle above. The `:boom:` subsection goes first
in the version, before `:rocket:` (matching the existing precedent).

**Preserve highlighted entries:** if an entry already sits under
`### :rocket: Epics and highlights`, keep it there when refreshing. Do not
move it down just because its type would place it under `:sparkles:`.

**Community attribution:** if the issue or its fix PR has the
`community contribution` label, add `(by @<github_username>)` on the entry
line, **before** the issue/PR references. Use the **PR author** (the `author`
field from step 4), not the issue author:

```markdown
- Fix description of the bug (by @username) [#<ISSUE>](...) (PR: [#<PR>](...))
```

**Only closed issues are included**, even if open ones are tracked in the
milestone.

**Pairing rules:**

| Pattern | Changelog format |
|---------|-----------------|
| Closed issue + one or more fix PRs | Primary link = issue, PRs inline comma-separated |
| PR with no linked issue | Link the issue if a matching closed one exists in the milestone; otherwise skip (the issue is the changelog unit) |
| Closed issue with no fix PR in milestone | Link the issue directly, no PR reference |

> **False-positive associations:** a PR may wrongly claim to close an issue
> from another context (ancient PR, cross-project reference). If titles are
> clearly unrelated or the PR predates the issue by years, treat it as a data
> glitch and skip it.

### 5a. Verify PR merge status before writing

A closed issue may list closing PRs that were **closed without merging**
(e.g. a superseded community PR). Only **merged** PRs go in the changelog:

```bash
python3 scripts/changelog.py check-merged <ALL_PR_NUMBERS>
# also accepts: --file prs.txt, or numbers via --stdin
```

If a closing PR is closed-unmerged, find the merged PR that superseded it
(other PRs in the issue's closing list, similar titles, or pointers in the
closed PR's timeline) and reference that one instead.

### 5b. Security advisory (GHSA) entries

Advisories fixed in a release go in the changelog even though they are
**neither milestone issues nor PRs**. The GHSA ID and description come from
the user or the release notes — never from the milestone fetch.

```markdown
- Fix <user-facing description> (https://github.com/penpot/penpot/security/advisories/GHSA-XXXX-XXXX-XXXX)
```

Rules: place under `### :bug: Bugs fixed` with **no issue or PR link**; do
**not** fetch or verify the URL (it may be draft/unpublished and 404); write
the description in imperative mood from the advisory title. These entries are
invisible to the automation — add them by hand, and in step 9 apply only the
backport/duplicate check to them.

### 6. Read the current CHANGES.md and run pre-flight checks

Newest version goes at the top, right after the `# CHANGELOG` header:

```markdown
## <VERSION>

### :boom: Breaking changes & Deprecations

- <breaking change or deprecation> [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))

### :bug: Bugs fixed

- Fix description of the bug [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
- Fix another bug (by @contributor) [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))

### :sparkles: New features & Enhancements

- Add new feature description [#<ISSUE>](https://github.com/penpot/penpot/issues/<ISSUE>) (PR: [#<PR>](https://github.com/penpot/penpot/pull/<PR>))
```

Format details: entries start with `- ` plus a short imperative description;
PR refs stay inline (`(PR: [#<N>](<url>))`, comma-separated for several);
`(by @<username>)` goes before the issue link; only include non-empty
sections; blank line between a section's last entry and the next title; never
duplicate an entry from an earlier version section (the earlier version wins
for backports).

**Pre-flight checks — fix violations directly in `CHANGES.md` before writing
the new section.** Reconcile every existing entry (any version section) and
every milestone candidate against the current milestone state (re-fetch, do
not trust cached data):

1. **Duplicate across versions** → remove from the current section.
2. **Stale milestone assignment** (issue moved out of this milestone) →
   remove the entry (or drop it if the section does not exist yet).
3. **Newly applied exclusion labels** (`no changelog`, `release blocker`) →
   remove the entry.
4. **Issue no longer closed/deleted/Rejected** → remove the entry.
5. **Unmerged or moved PR reference** → fix the reference or remove the
   entry (a PR merged in a *different* milestone is a step-9 anomaly, do not
   silently remove it).
6. **Issue type changed to `Task`** → remove the entry.
7. **Breaking change misplaced** (issue has the `breaking change` label but
   sits in another section) → move the entry to `:boom:`.
8. **Missing valid issues** (closed, non-excluded, unreferenced anywhere) →
   add them to the current section per step 5.

### 7. Build the description text

Derive it from the **issue title**, not the PR title. Strip leading emoji
prefixes (`:bug:`, `:sparkles:`, `:tada:`) and describe the user-facing
behavior:

| Issue title | Changelog description |
|-------------|----------------------|
| `Plugin API token methods fail with schema validation error on PRO` | `Fix Plugin API token methods failing with schema validation error on PRO` |
| `Comment content is not sanitized before rendering, enabling stored XSS` | `Sanitize comment content on rendering` |
| `Custom uploaded font family names are not sanitized` | `Sanitize font family names on custom uploaded fonts` |

Insert the new version section right after the `# CHANGELOG` header with the
`edit` tool and enough context for a unique match.

### 7b. Propose and populate `:rocket: Epics and highlights`

Create the subsection if missing (place it before `### :sparkles:`) and pick
2–5 of the most impactful/user-visible `:sparkles:` entries: new visible
features, big capabilities, items that make self-hosted users want to update.
`frontend/src/app/main/ui/releases/v2_<MINOR>.cljs` slide titles are optional
hints (they may not exist for every version). Every `:rocket:` entry MUST
carry issue AND PR references; never remove entries from a prior run.

### 8. Cross-reference milestone PRs against the changelog

```bash
python3 scripts/changelog.py cross-ref "<MILESTONE>" [--changes CHANGES.md]
```

Lists merged milestone PRs missing from the changelog section (decide per PR:
add it or confirm its exclusion labels) and warns about CLOSED (unmerged) PRs
in the milestone. GHSA entries never appear here — that absence is expected.

### 9. Generate the anomaly report

```bash
python3 scripts/changelog.py report "<MILESTONE>" [--changes CHANGES.md --output CHANGES-ISSUES.md]
```

Overwrites `CHANGES-ISSUES.md` with the current state. Every number renders as
a full `[#N](https://github.com/penpot/penpot/issues/N)` or
`[#N](https://github.com/penpot/penpot/pull/N)` link.

**An anomaly is a milestone mismatch or a `:boom:` mislabel** (the changelog
pairing is misleading and a human must judge intent):

1. Issue in this milestone, referenced PR in another milestone (or none).
2. PR in this milestone, closed issue in another milestone — except an issue
   with *no* milestone, which belongs to another (probably private) project
   and is neither anomaly nor changelog candidate.
3. `:boom:` entry whose issue lacks the `breaking change` label. These stay
   listed in the report **and** in the changelog: either label the issue or
   move the entry to its regular section.

**Highlight gaps are warnings, not anomalies:** missing `:rocket:` on a
released X.Y.0 (patches never carry highlights); `:rocket:` entry without
issue AND PR references. Gaps never count toward the anomaly total.

**Anything else is a rule violation**, not a report item: fix it in step 6
pre-flight. If one shows up in the report, re-run the workflow.

## Key Principles

- **Issue = changelog unit**, PR = implementation detail inline.
- **Latest version first**, below the `# CHANGELOG` header.
- **Issue Type decides the section — exclusively**, except `breaking change`
  label → `:boom:` first.
- **User-facing descriptions** in imperative mood.
- **Community attribution** uses the PR author, placed before the issue link.
- **Only closed issues**; **Rejected** project status excludes.
- **`no changelog` and `Task`** stay out.
- **Multiple fix PRs** go comma-separated inline.
- **Duplicates**: the earlier version section wins.
- **Taiga references**: resolve to the GitHub issue via description text or
  PRs mentioning the Taiga URL, then link issue + PR.
- **GHSA entries** live under `:bug:` with only the advisory URL (see 5b).
- **Re-fetch before editing**; prefer `scripts/gh.py` over raw `gh api`.
- **Verify PR merge status** (`check-merged`); PR-level exclusions apply.
- **Cross-reference PRs, not just issues** (`cross-ref`); watch for
  false-positive PR-to-issue links.
