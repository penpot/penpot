---
name: update-changelog
description: Update the project CHANGES.md with issues from a given GitHub milestone, with correct categorization and references.
---

# Skill: update-changelog

Entry point for all changelog work. All rules — sections, exclusions, entry
format, the `:boom:`, `:rocket:` and `:lock:` contracts, pre-flight, and the
anomaly report — live in `mem:workflow/updating-changelog` (file:
`.serena/memories/workflow/updating-changelog.md`). This skill routes to that
memory; it does not repeat it.

## When to Use

- Before a new release, to populate the changelog with all fixed issues
- When new issues are added to an existing milestone and the changelog needs
  to be refreshed
- To audit or repair an existing version section

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)
- Python 3.8+
- `scripts/gh.py` for GitHub queries, `scripts/changelog.py` for changelog
  checks (run `python3 scripts/changelog.py <command> --help` for usage)

## What to Read

1. `mem:workflow/updating-changelog` — the canonical flow: sources of truth,
   layout, entry rules, the `## Breaking change note` comment contract, the
   advisory (`:lock:`) contract, pre-flight checks, anomaly types, and pitfalls.
2. `mem:scripts/changelog` — `boom`, `security`, `check-merged`, `cross-ref`,
   `dev-only-bugs`, `report`.
3. `mem:scripts/gh` — `issues`, `issue`, `comments`, `prs`, `advisories`.

## Commands

```bash
python3 scripts/gh.py issues "<VERSION>" --exclude "release blocker,no changelog"
python3 scripts/gh.py advisories --patched-version "<VERSION>"
python3 scripts/changelog.py dev-only-bugs "<VERSION>"            # list bugs that never reached production
python3 scripts/changelog.py dev-only-bugs "<VERSION>" --apply    # label the sure ones `no changelog`
python3 scripts/changelog.py check-merged <PR numbers...>
python3 scripts/changelog.py boom "<VERSION>"            # print the :boom: section
python3 scripts/changelog.py boom "<VERSION>" --write    # replace it in CHANGES.md
python3 scripts/changelog.py security "<VERSION>"        # print the :lock: section
python3 scripts/changelog.py security "<VERSION>" --write # replace it in CHANGES.md
python3 scripts/changelog.py cross-ref "<VERSION>"
python3 scripts/changelog.py report "<VERSION>"
```

## Hard Rules

- `CHANGES.md` is edited only on explicit request or during the release process.
- Only closed, non-excluded milestone issues are included; the issue is the changelog unit and the PR is inline.
- `:boom:` and `:rocket:` are label-generated and `:lock:` is advisory-generated: never hand-edit them; edit the label, the `## Breaking change note` comment or the advisory and re-run the command.
- Verify PR merge status before writing; only merged PRs are referenced.
- Bugs that never reached production stay out: `dev-only-bugs --apply` labels the sure ones; candidates are the user's call, never label them yourself.
- Fill `patched_versions` in the advisories before running `security`; unpublished advisories are valid.
