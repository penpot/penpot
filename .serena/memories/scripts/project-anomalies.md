# Project anomalies helper

`scripts/project-anomalies.py` implements the `find-project-anomalies` skill: `check <MILESTONE>` fetches milestone issues/PRs with `--state all` via `scripts/gh.py`, resolves outsiders in bulk (one GraphQL call per 50 items), and writes `tmp/<MILESTONE>-ANOMALIES.md` (gitignored scratch output, every number a full GitHub link).

## Anomaly types (all scoped to the milestone)

- Open-with-merged-PR: OPEN issue with a MERGED closing PR (close it or move it out).
- Issue-PR mismatch: milestone issue whose *merged* closing PR is elsewhere/nowhere (an unmerged PR's milestone is irrelevant), or issue off the Main board.
- PR-issue mismatch: *merged* milestone PR closing an issue elsewhere/nowhere (milestone-less issues ARE reported here, unlike the changelog flow), or off the Main board. Unmerged PRs never count anywhere: their milestone is meaningless.
- Needs-triage: MERGED PR on a `needs triage` issue (human triage only, never auto-unlabel).
- Unassigned: MERGED PR on an issue with no assignees and no `community contribution` label on either side (valid states: assigned, or unassigned+community).

## Conventions

- Project membership is checked on the issue side only (`projects` list from `gh.py`); PRs are not the Main-tracked unit, so an unprojected PR alone is never an anomaly.
- Detection (`find_anomalies`) and rendering (`render_report`) are pure functions over pre-resolved dicts; outsiders must be merged into the lookup maps first (missing numbers are skipped, never flagged).
- The only mechanical fix is assigning a missing milestone (`gh pr/issue edit --milestone`); everything else is human judgment with per-item confirmation.
