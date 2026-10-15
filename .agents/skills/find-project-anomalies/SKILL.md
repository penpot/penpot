---
name: find-project-anomalies
description: Check a GitHub milestone against the Main project board, report the five anomaly types to tmp/<MILESTONE>-ANOMALIES.md, and fix missing milestone assignments on request.
---

# Skill: find-project-anomalies

Check every issue and PR in a given GitHub milestone against the Main
project board. Report the five anomaly types below, then fix the ones a
human confirms.

## Getting the version ($ARGUMENTS)

The milestone version comes from the skill arguments (`$ARGUMENTS`), the
same way other skills receive theirs. Accept `2.17.0`, `v2.17.0`,
`milestone 2.17.0`, or any text clearly containing one `X.Y.Z` version.

If no version is given and none can be deduced from the arguments or the
conversation context, **stop and ask the user** which milestone to check.
Do not guess, do not default to the latest release.

## When to Use

- Before a release, to catch board/milestone drift while there is still time
  to fix it
- After triage sessions, to verify every milestone item is tracked, owned,
  and correctly assigned
- Whenever someone asks "is milestone X.Y.Z clean on Main?"

## Prerequisites

- `gh` CLI authenticated (`gh auth status`)
- Python 3.8+
- `scripts/gh.py` for GitHub queries, `scripts/project-anomalies.py` for
  detection and reporting (`python3 scripts/project-anomalies.py check --help`)

## Workflow

### 1. Resolve the version (see above), then run the check

```bash
python3 scripts/project-anomalies.py check "2.17.0"
# explicit path:
python3 scripts/project-anomalies.py check "2.17.0" --output tmp/2.17.0-ANOMALIES.md
```

This fetches milestone issues and PRs in bulk (one GraphQL call per 50
items), resolves outsiders the same way, and overwrites
`tmp/<MILESTONE>-ANOMALIES.md`. Every number renders as a full
`[#N](https://github.com/penpot/penpot/issues/N)` or
`[#N](https://github.com/penpot/penpot/pull/N)` link.

### 2. Read the report and judge each anomaly

The five types, in report order:

1. **OPEN issue with a MERGED PR** — the fix landed but the issue never
   closed (or it reopened). Fix: close the issue, or move it out of the
   milestone if the fix did not actually land here.
2. **Milestone issue, PR elsewhere or issue off Main** — a *merged* closing
   PR in another milestone (or none), or the issue itself not on the Main
   board. An unmerged PR's milestone means nothing (it landed nowhere), so
   it never triggers this type. Fix: align the milestones (see step 3), or
   add the issue to Main.
3. **Milestone PR, issue elsewhere or off Main** — the mirror view over
   *merged* PRs only: a PR released here closes an issue tracked elsewhere
   (or nowhere on Main). Unmerged PRs never trigger this type — their
   milestone means nothing yet. Fix: align the milestones (see step 3), or
   add the issue to Main.
4. **MERGED PR on a `needs triage` issue** — landed without triage. Only a
   human can triage; never auto-remove the label.
5. **MERGED PR on an unassigned, non-community issue** — no owner and no
   `community contribution` label (checked on both issue and PR). Either
   assign an owner or confirm it is community work. Never invent an owner.

Scope notes (deliberate, not bugs):
- Project membership is checked **on the issue side only**. PRs are not the
  unit tracked on Main, so an unprojected PR alone is never an anomaly.
- An issue with *no* milestone closed by a milestone PR **is** reported
  here (type 3): unlike the changelog flow, this pairing always needs a
  human look.

### 3. Fix missing milestone assignments (only these, only on confirmation)

The only mechanical fix in this skill is assigning a missing milestone.
The report already prints the exact command next to each such case:

```bash
gh pr edit <PR> --milestone "<MILESTONE>"
gh issue edit <ISSUE> --milestone "<MILESTONE>"
```

Rules:
- Apply **only** when one side lacks a milestone. When both sides have
  *different* milestones, a human decides which one moves — never pick a
  side yourself.
- Confirm **each fix (or each small batch)** with the user before running
  it. Read back what the command will change.
- Everything else (closing issues, triaging, assigning owners, adding
  items to the Main board) is human work: point at it, do not do it.

### 4. Re-run until clean

After fixes land, re-run step 1 and confirm the report shows zero
anomalies. The `tmp/` report is scratch output (gitignored): quote or
paste entries into chat when reporting back, do not commit it.

## Key Principles

- **No version, no run.** Stop and ask when the milestone is unknown.
- **Report first, touch nothing.** No board or milestone changes without
  explicit per-item confirmation.
- **Milestone gaps auto-fix; judgment calls do not.** Only a missing
  milestone is mechanical. triage, ownership, and close/reopen decisions
  belong to humans.
- **Every number clickable.** The report is useless without full GitHub links.
