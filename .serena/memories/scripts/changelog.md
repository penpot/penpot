# Changelog helper

`scripts/changelog.py` holds the checks used by the `update-changelog` skill (extracted from the skill file so the skill stays workflow-only). Calls `scripts/gh.py` via subprocess; never call the GitHub API directly from this script.

## Subcommands

- `check-merged <PR...>` (`--file`, `--stdin` accepted) — warn on any non-merged PR, exit 1 when found; run before writing changelog entries.
- `cross-ref <MILESTONE> [--changes CHANGES.md]` — fetch milestone PRs with `--state all` once, report merged PRs missing from the version section plus CLOSED (unmerged) warnings; GHSA entries never appear here by design.
- `report <MILESTONE> [--changes CHANGES.md --output CHANGES-ISSUES.md]` — overwrite the anomaly report (milestone mismatches type A/B plus `:rocket:` gaps C/D); rule violations never belong in the report, they are fixed in CHANGES.md first.

## Conventions

- Milestone resolution is batched, never per-item: unknown PRs go through `gh.py prs <numbers>` and unknown issues through `gh.py issue <numbers>` (one GraphQL call per 50 items); a lookup miss resolves to null, never to an extra call.

- Version-section lookup accepts an optional `(suffix)` after the version header (e.g. `(Unreleased)`); a missing section is an error for `cross-ref` and an empty section for `report`.
- PR/issue reference parsing covers standard `[#N](.../pull/N)` / `[#N](.../issues/N)` links plus legacy `PR:[N]` / `[Github #N]` forms.
- Shared parsing helpers (`extract_version_section`, `extract_subsection`, `collect_changelog_prs/issues`) live at module top so future subcommands reuse them instead of duplicating regexes.
- Report anomaly types: A/B milestone mismatches, E `:boom:` entry whose issue lacks the `breaking change` label (counted, entries preserved — human labels the issue or moves the entry); C/D are `:rocket:` gaps (warnings, never counted).
