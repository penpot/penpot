#!/usr/bin/env python3
"""
project-anomalies.py — Find anomalies on the Main project board for a milestone.

Used by the ``find-project-anomalies`` skill. Fetches milestone issues and
PRs in bulk via ``scripts/gh.py`` (one GraphQL call per 50 items, never one
call per item) and writes a clickable report to
``tmp/<MILESTONE>-ANOMALIES.md``.

Anomaly types (all scoped to the given milestone):
  1. open-with-merged-pr — OPEN issue with a MERGED closing PR.
  2. issue-pr-mismatch — milestone issue whose closing PR is in a different
     milestone (or none), or issue not associated to the Main project.
  3. pr-issue-mismatch — milestone PR whose closed issue is in a different
     milestone (or none), or not associated to the Main project.
  4. needs-triage — MERGED PR whose closed issue has the `needs triage` label.
  5. unassigned — MERGED PR whose closed issue has no assignees and no
     `community contribution` label (on the issue or the PR).

Project membership is only checked on the issue side: PRs are not the unit
tracked on the Main board, so an unprojected PR alone is never an anomaly.

Usage:
  python3 scripts/project-anomalies.py check "2.17.0"
  python3 scripts/project-anomalies.py check "2.17.0" --output tmp/2.17.0-ANOMALIES.md

Prerequisites:
  - gh CLI authenticated (gh auth status)
  - Python 3.8+
"""

import argparse
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


REPO = "penpot/penpot"
GH_PY = Path(__file__).resolve().parent / "gh.py"
MAIN_PROJECT = "Main"
COMMUNITY_LABEL = "community contribution"
TRIAGE_LABEL = "needs triage"


# ─────────────────────────────────────────────
#  Shared helpers
# ─────────────────────────────────────────────


def run_gh_py(*args: str) -> str:
    """Run ``scripts/gh.py`` with the given arguments, return stdout."""
    cmd = [sys.executable, str(GH_PY), *args]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"gh.py error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout


def issue_url(n: int) -> str:
    return f"https://github.com/{REPO}/issues/{n}"


def pr_url(n: int) -> str:
    return f"https://github.com/{REPO}/pull/{n}"


def issue_link(n: int) -> str:
    return f"[#{n}]({issue_url(n)})"


def pr_link(n: int) -> str:
    return f"[#{n}]({pr_url(n)})"


def issue_link_title(n: int, title: str) -> str:
    url = issue_url(n)
    if title:
        return f"[#{n}]({url}) — [{title}]({url})"
    return f"[#{n}]({url})"


def pr_link_title(n: int, title: str) -> str:
    url = pr_url(n)
    if title:
        return f"[#{n}]({url}) — [{title}]({url})"
    return f"[#{n}]({url})"


def in_main_project(item: dict) -> bool:
    """Whether an issue/PR dict is associated to the Main project board."""
    return MAIN_PROJECT in (item.get("projects") or [])


# ─────────────────────────────────────────────
#  Anomaly detection (pure: no network inside)
# ─────────────────────────────────────────────


def find_anomalies(
    milestone: str,
    issues: list[dict],
    prs: list[dict],
    pr_by_num: dict[int, dict],
    issue_by_num: dict[int, dict],
) -> dict[str, list[dict]]:
    """Detect the five anomaly types for a milestone.

    Callers pre-resolve outsiders: any closing PR/issue not in the milestone
    must already sit in ``pr_by_num``/``issue_by_num`` (with at least
    ``milestone``, ``state``, ``labels``, ``assignees`` and ``projects``).
    Unknown numbers are skipped, never flagged.
    """
    t1_open_merged: list[dict] = []
    t2_issue_pr: list[dict] = []
    t3_pr_issue: list[dict] = []
    t4_needs_triage: list[dict] = []
    t5_unassigned: list[dict] = []

    for issue in issues:
        if issue.get("state") != "OPEN":
            continue
        merged = [
            n for n in issue.get("closing_prs", [])
            if (pr_by_num.get(n) or {}).get("state") == "MERGED"
        ]
        if merged:
            t1_open_merged.append({
                "issue": issue["number"],
                "issue_title": issue.get("title", ""),
                "prs": sorted(merged),
            })

    for issue in issues:
        for pr_num in issue.get("closing_prs", []):
            pr = pr_by_num.get(pr_num)
            if pr is None:
                continue
            # An unmerged PR has landed nowhere: its milestone says nothing
            # about any release, so only merged PRs count here.
            if pr.get("state") != "MERGED":
                continue
            if pr.get("milestone") != milestone:
                t2_issue_pr.append({
                    "issue": issue["number"],
                    "issue_title": issue.get("title", ""),
                    "pr": pr_num,
                    "pr_milestone": pr.get("milestone"),
                })
        if not in_main_project(issue):
            t2_issue_pr.append({
                "issue": issue["number"],
                "issue_title": issue.get("title", ""),
                "pr": None,
                "pr_milestone": None,
                "projects": issue.get("projects") or [],
            })

    for pr in prs:
        # Like type 2: only a merged PR has landed somewhere, so only
        # merged PRs take part in milestone pairing. (Whether the issue is
        # on the Main board is checked regardless.)
        if pr.get("state") != "MERGED":
            continue
        for issue_num in pr.get("closing_issues", []):
            issue = issue_by_num.get(issue_num)
            if issue is None:
                continue
            if issue.get("milestone", milestone) != milestone or not in_main_project(issue):
                t3_pr_issue.append({
                    "pr": pr["number"],
                    "pr_title": pr.get("title", ""),
                    "issue": issue_num,
                    "issue_title": issue.get("title", ""),
                    "issue_milestone": issue.get("milestone", milestone),
                    "projects": issue.get("projects") or [],
                })

    for pr in prs:
        if pr.get("state") != "MERGED":
            continue
        for issue_num in pr.get("closing_issues", []):
            issue = issue_by_num.get(issue_num)
            if issue is None:
                continue
            if TRIAGE_LABEL in (issue.get("labels") or []):
                t4_needs_triage.append({
                    "pr": pr["number"],
                    "pr_title": pr.get("title", ""),
                    "issue": issue_num,
                    "issue_title": issue.get("title", ""),
                })
            if not issue.get("assignees") and COMMUNITY_LABEL not in (
                (issue.get("labels") or []) + (pr.get("labels") or [])
            ):
                t5_unassigned.append({
                    "pr": pr["number"],
                    "pr_title": pr.get("title", ""),
                    "issue": issue_num,
                    "issue_title": issue.get("title", ""),
                })

    return {
        "open_merged": t1_open_merged,
        "issue_pr": t2_issue_pr,
        "pr_issue": t3_pr_issue,
        "needs_triage": t4_needs_triage,
        "unassigned": t5_unassigned,
    }


# ─────────────────────────────────────────────
#  Report rendering (pure)
# ─────────────────────────────────────────────


def milestone_fix(kind: str, number: int, milestone: str) -> str:
    """Render the one-line `gh` command that assigns a missing milestone."""
    return f"Fix: `gh {kind} edit {number} --milestone \"{milestone}\"`"


def render_report(
    milestone: str,
    anomalies: dict[str, list[dict]],
    total_issues: int,
    closed_issues: int,
    total_prs: int,
    merged_prs: int,
) -> str:
    """Render the full anomalies report as Markdown."""
    t1 = anomalies["open_merged"]
    t2 = anomalies["issue_pr"]
    t3 = anomalies["pr_issue"]
    t4 = anomalies["needs_triage"]
    t5 = anomalies["unassigned"]
    total = len(t1) + len(t2) + len(t3) + len(t4) + len(t5)

    lines = [
        f"# Project Anomalies — {milestone}",
        "",
        f"Generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}",
        "",
        "---",
        "",
        "## Summary",
        "",
        f"- **OPEN issue with a MERGED PR:** {len(t1)}",
        f"- **Milestone issue, PR elsewhere/unprojected:** {len(t2)}",
        f"- **Milestone PR, issue elsewhere/unprojected:** {len(t3)}",
        f"- **MERGED PR on a `needs triage` issue:** {len(t4)}",
        f"- **MERGED PR on an unassigned, non-community issue:** {len(t5)}",
        f"- **Total anomalies:** {total}",
        "",
    ]

    if total == 0:
        lines += [
            "✅ No anomalies found. The milestone and the Main project board agree.",
            "",
        ]
    else:
        lines += ["## Anomalies", ""]
        if t1:
            lines += [
                "### OPEN issue with a MERGED PR",
                "",
                "The fix already landed but the issue never closed (or it was "
                "reopened). Close the issue or move it out of the milestone.",
                "",
            ]
            for e in t1:
                lines.append(f"- {issue_link_title(e['issue'], e['issue_title'][:80])}")
                for n in e["prs"]:
                    lines.append(f"  - 🔴 Merged {pr_link(n)}")
                lines.append("")

        if t2:
            lines += [
                "### Milestone issue, PR elsewhere or issue off the Main board",
                "",
            ]
            by_issue: dict[int, list[dict]] = {}
            for e in t2:
                by_issue.setdefault(e["issue"], []).append(e)
            for issue_num in sorted(by_issue):
                entries = by_issue[issue_num]
                lines.append(
                    f"- {issue_link_title(issue_num, entries[0]['issue_title'][:80])}"
                )
                for e in entries:
                    if e["pr"] is None:
                        projects = ", ".join(e["projects"]) or "_none_"
                        lines.append(
                            "  - 🔴 Issue is not on the Main project board "
                            f"(projects: {projects})"
                        )
                    else:
                        ms = e["pr_milestone"] or "_none_"
                        badge = "🔴" if e["pr_milestone"] is None else "⚠️"
                        lines.append(
                            f"  - {badge} Closing {pr_link(e['pr'])} is in milestone "
                            f"**{ms}** (expected: {milestone})"
                        )
                        if e["pr_milestone"] is None:
                            lines.append(
                                f"    {milestone_fix('pr', e['pr'], milestone)}"
                            )
                lines.append("")

        if t3:
            lines += [
                "### Milestone PR, issue elsewhere or off the Main board",
                "",
            ]
            by_pr: dict[int, list[dict]] = {}
            for e in t3:
                by_pr.setdefault(e["pr"], []).append(e)
            for pr_num in sorted(by_pr):
                entries = by_pr[pr_num]
                lines.append(
                    f"- {pr_link_title(pr_num, entries[0]['pr_title'][:80])}"
                )
                for e in entries:
                    ms = e["issue_milestone"] or "_none_"
                    if e["issue_milestone"] != milestone:
                        lines.append(
                            f"  - ⚠️ Closing {issue_link(e['issue'])} is in milestone "
                            f"**{ms}** (expected: {milestone})"
                        )
                        if e["issue_milestone"] is None:
                            lines.append(
                                f"    {milestone_fix('issue', e['issue'], milestone)}"
                            )
                    else:
                        projects = ", ".join(e["projects"]) or "_none_"
                        lines.append(
                            f"  - 🔴 Closing {issue_link(e['issue'])} is not on the "
                            f"Main project board (projects: {projects})"
                        )
                lines.append("")

        if t4:
            lines += [
                "### MERGED PR on a `needs triage` issue",
                "",
                "The fix landed but the issue was never triaged. Human triage needed.",
                "",
            ]
            for e in t4:
                lines.append(
                    f"- ⚠️ {pr_link_title(e['pr'], e['pr_title'][:80])}"
                    f" closes {issue_link(e['issue'])}"
                )
            lines.append("")

        if t5:
            lines += [
                "### MERGED PR on an unassigned, non-community issue",
                "",
                "No owner and no community label. Assign an owner or confirm it "
                "is a community contribution.",
                "",
            ]
            for e in t5:
                lines.append(
                    f"- ⚠️ {pr_link_title(e['pr'], e['pr_title'][:80])}"
                    f" closes {issue_link(e['issue'])}"
                )
            lines.append("")

    lines += [
        "---",
        "",
        "## Context",
        "",
        f"- Milestone: **{milestone}**",
        f"- Milestone issues (all states): {total_issues}",
        f"- Open issues in milestone: {total_issues - closed_issues}",
        f"- Milestone PRs (all states): {total_prs}",
        f"- Merged PRs in milestone: {merged_prs}",
        "",
    ]
    return "\n".join(lines)


# ─────────────────────────────────────────────
#  CLI entrypoint
# ─────────────────────────────────────────────


def cmd_check(args: argparse.Namespace) -> None:
    """Fetch milestone data in bulk, detect anomalies, write the report."""
    milestone = args.milestone

    all_issues = json.loads(run_gh_py("issues", milestone, "--state", "all"))
    issue_by_num = {i["number"]: i for i in all_issues}

    all_prs = json.loads(run_gh_py("prs", "--milestone", milestone, "--state", "all"))
    pr_by_num = {p["number"]: p for p in all_prs}

    # Resolve outsiders in bulk (one GraphQL call per 50 items).
    unknown_prs = set()
    for issue in all_issues:
        unknown_prs.update(issue.get("closing_prs", []))
    unknown_prs -= set(pr_by_num)
    if unknown_prs:
        print(
            f"Resolving {len(unknown_prs)} PRs outside {milestone}...",
            file=sys.stderr,
        )
        for pr in json.loads(run_gh_py("prs", *[str(n) for n in sorted(unknown_prs)])):
            pr_by_num[pr["number"]] = pr

    unknown_issues = set()
    for pr in all_prs:
        unknown_issues.update(pr.get("closing_issues", []))
    unknown_issues -= set(issue_by_num)
    if unknown_issues:
        print(
            f"Resolving {len(unknown_issues)} issues outside {milestone}...",
            file=sys.stderr,
        )
        for issue in json.loads(
            run_gh_py("issue", *[str(n) for n in sorted(unknown_issues)])
        ):
            if "error" not in issue:
                issue_by_num[issue["number"]] = issue

    anomalies = find_anomalies(milestone, all_issues, all_prs, pr_by_num, issue_by_num)
    report = render_report(
        milestone,
        anomalies,
        total_issues=len(all_issues),
        closed_issues=sum(1 for i in all_issues if i.get("state") == "CLOSED"),
        total_prs=len(all_prs),
        merged_prs=sum(1 for p in all_prs if p.get("state") == "MERGED"),
    )

    output = Path(args.output or f"tmp/{milestone}-ANOMALIES.md")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report)
    total = sum(len(v) for v in anomalies.values())
    print(f"{total} anomalies written to {output}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Find Main-project anomalies for a milestone"
    )
    sub = parser.add_subparsers(dest="command", required=True, title="subcommands")

    p_check = sub.add_parser("check", help="Detect anomalies and write the report")
    p_check.add_argument("milestone", help="Milestone title (e.g. '2.17.0')")
    p_check.add_argument(
        "--output",
        default=None,
        help="Report path (default: tmp/<MILESTONE>-ANOMALIES.md)",
    )
    p_check.set_defaults(func=cmd_check)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
