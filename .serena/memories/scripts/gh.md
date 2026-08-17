# GitHub operations helper

`scripts/gh.py` is a multi-purpose CLI for querying the penpot/penpot GitHub
repository via GraphQL and REST APIs through the authenticated `gh` CLI.

## When to use

- Listing issues in a milestone (for changelog generation).
- Finding issues with no milestone.
- Fetching PR details by number or by milestone.
- Comparing milestone issues against CHANGES.md to find missing entries.

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

**Output**: JSON array to stdout; progress to stderr.

### `prs`

Fetch PR details by number or by milestone.

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

## Key principles

- All output is JSON — pipe into `jq` or other tools for further processing.
- Milestone lookup is by exact title match.
- `issues` subcommand auto-paginates (100 items per page).
- `prs` subcommand batches PR number lookups (50 per GraphQL query).
