# GitHub operations helper

`scripts/gh.py` is a multi-purpose CLI for querying the penpot/penpot GitHub
repository via GraphQL and REST APIs through the authenticated `gh` CLI.

## When to use

- Listing issues in a milestone (for changelog generation).
- Finding issues with no milestone.
- Fetching PR details by number or by milestone.
- Comparing milestone issues against CHANGES.md to find missing entries.
- Explicitly linking a GitHub issue to a pull request.
- Listing or inspecting GitHub Security Advisories (GHSA).
- Fetching issue comments (the `## Breaking change note` markdown for the changelog).

## Prerequisites

- `gh` CLI authenticated (`gh auth status`).
- Python 3.8+.

## Subcommands

### `issues`

List issues in a milestone, with filtering by state, labels, and project status.

```bash
# Closed issues in a milestone (default)
python3 scripts/gh.py issues "2.16.0"

# All issues in a milestone
python3 scripts/gh.py issues "2.16.0" --state all

# Issues with no milestone
python3 scripts/gh.py issues none
python3 scripts/gh.py issues none --state open

# Filter by label (include only)
python3 scripts/gh.py issues "2.16.0" --label "bug"
python3 scripts/gh.py issues "2.16.0" --label "bug,regression"

# Exclude by label
python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog"

# Show only issues NOT yet in CHANGES.md
python3 scripts/gh.py issues "2.16.0" --compare CHANGES.md
```

**Default filters** (override with flags):
- Issues with type "Task" are excluded (`--include-tasks` to keep them).
- Issues with "Rejected" project status are excluded (`--include-rejected` to keep them).

Every issue entry carries `assignees` (logins) and `projects` (board titles); every PR entry carries `assignees`.

**Output**: JSON array to stdout; progress to stderr.

### `prs`

Fetch PR details by number or by milestone. Every entry includes a `milestone` field (title or null).

```bash
# Fetch specific PRs
python3 scripts/gh.py prs 9179 9204 9311

# Read PR numbers from file
python3 scripts/gh.py prs --file prs.txt

# Read PR numbers from stdin
cat prs.txt | python3 scripts/gh.py prs --stdin

# All PRs in a milestone (default: merged only)
python3 scripts/gh.py prs --milestone "2.16.0"

# All PRs in a milestone (all states)
python3 scripts/gh.py prs --milestone "2.16.0" --state all
```

**Output**: JSON array to stdout; progress to stderr.

### `issue`

Fetch issue details by number in batches of 50 (same input styles as `prs`: numbers, `--file`, `--stdin`). No filters. A dead number fails its whole batch (unlike PRs, issues error instead of resolving to null), so the batch falls back to one-by-one lookups and reports misses as `{"number", "error": "not_found"}`. Any other failure aborts.

**Output**: JSON array to stdout; progress to stderr.

### `comments`

Fetch every comment of one or more issues via REST (paginated, 100 per page), oldest first. Same input styles as `issue`: numbers, `--file`, `--stdin`.

```bash
python3 scripts/gh.py comments 11235 11236
```

**Output fields per comment**: `issue`, `id`, `author`, `created_at`, `body`. JSON array to stdout; progress to stderr. `changelog.py boom` reads the `## Breaking change note` comment from this output.

### `link-issue`

Explicitly assign a GitHub issue to a pull request:

```bash
python3 scripts/gh.py link-issue <ISSUE_NUMBER> <PR_NUMBER>
# Short alias: python3 scripts/gh.py link <ISSUE_NUMBER> <PR_NUMBER>
```

The command resolves both node IDs and calls `addCloseIssueReferences`. It trusts the successful mutation instead of re-querying, because GitHub does not reliably report mutation-created links through `closedByPullRequestsReferences(userLinkedOnly: true)`. It is safe to rerun and works for merged PRs. It does not guarantee the issue stays open: adding `Closes #N` to the body of an already-merged PR closed the issue immediately when tried (#12896 / PR #12067, 2026-11-09). JSON goes to stdout; progress and errors go to stderr; a missing issue/PR or a failed mutation exits non-zero.

### `advisories`

List or inspect GitHub Security Advisories for the repository.

```bash
# List all advisories (summary view)
python3 scripts/gh.py advisories

# Filter by severity
python3 scripts/gh.py advisories --severity critical

# Filter by state
python3 scripts/gh.py advisories --state triage

# Filter by patched version declared in the advisory
python3 scripts/gh.py advisories --patched-version 2.19.1

# Get full detail for a single advisory
python3 scripts/gh.py advisories GHSA-xvj6-fh9w-gjw7
```

**Summary output fields**: ghsa_id, cve_id, severity, cvss_score, state, summary, cwes, published_at, closed_at, url.

**Detail output** (single advisory) adds: description, vulnerabilities (package, version ranges), credits, timestamps.

`--patched-version` filters the raw advisories before formatting: the match is an exact token of `vulnerabilities[*].patched_versions` (split on `,`/`;`), so `2.19.1` never matches `2.19.10`. Every state is included (`triage`, `draft`, `published`, `closed`, `withdrawn`). The raw `vulnerabilities` stay internal to the filter: the list output keeps the summary schema.

**Output**: JSON to stdout; progress to stderr.

## Key principles

- `issues --compare` collects every `#NNNN` token in the file regardless of its link target, so a same-numbered reference to another tracker (e.g. `[Taiga #12312](...)`) or an inline prose number falsely marks that GitHub issue as already listed; verify against `changelog.py cross-ref` when a number collides.
- Changelog-specific checks (merge-status verification, milestone-vs-changelog cross-reference, anomaly report) live in `scripts/changelog.py`, which calls this script via subprocess — see `mem:scripts/changelog`.

- All output is JSON — pipe into `jq` or other tools for further processing.
- Transient HTTP 504 responses from `gh` are retried automatically (3 attempts, 5s/15s backoff); any other error fails fast with no retry.
- Milestone lookup is by exact title match.
- `issues` subcommand auto-paginates (100 items per page).
- `prs` subcommand batches PR number lookups (50 per GraphQL query).
