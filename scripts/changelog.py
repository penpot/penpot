#!/usr/bin/env python3
"""
changelog.py — Helper commands for the ``update-changelog`` skill.

Uses ``scripts/gh.py`` (via subprocess) and the local ``CHANGES.md`` so the
skill file itself stays free of inline programs.

Subcommands:
  check-merged  Verify that every given PR is merged (warns otherwise)
  cross-ref     Compare merged milestone PRs against the changelog section
  report        Generate the anomaly report (CHANGES-ISSUES.md)

Usage:
  python3 scripts/changelog.py check-merged 9179 9204 9311
  cat prs.txt | python3 scripts/changelog.py check-merged --stdin
  python3 scripts/changelog.py cross-ref "2.16.0"
  python3 scripts/changelog.py report "2.16.0"
  python3 scripts/changelog.py report "2.16.0" --changes CHANGES.md --output CHANGES-ISSUES.md

Prerequisites:
  - gh CLI authenticated (gh auth status)
  - Python 3.8+
"""

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


REPO = "penpot/penpot"
GH_PY = Path(__file__).resolve().parent / "gh.py"


# ─────────────────────────────────────────────
#  Shared helpers
# ─────────────────────────────────────────────


def run_gh_py(*args: str, input_text: str | None = None) -> str:
    """Run ``scripts/gh.py`` with the given arguments, return stdout."""
    cmd = [sys.executable, str(GH_PY), *args]
    result = subprocess.run(cmd, input=input_text, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"gh.py error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    return result.stdout


def read_pr_numbers(args: argparse.Namespace) -> list[int]:
    """Collect PR numbers from positional args, --file, and/or --stdin."""
    numbers: list[int] = []
    if args.numbers:
        numbers.extend(args.numbers)
    if args.file:
        with open(args.file) as f:
            for line in f:
                line = line.strip()
                if line:
                    numbers.append(int(line))
    if args.stdin:
        for line in sys.stdin:
            line = line.strip()
            if line:
                numbers.append(int(line))
    seen: set[int] = set()
    return [n for n in numbers if not (n in seen or seen.add(n))]


def fetch_prs_by_numbers(pr_numbers: list[int]) -> list[dict]:
    """Fetch PR details via ``gh.py prs`` for explicit PR numbers."""
    stdout = run_gh_py("prs", *[str(n) for n in pr_numbers])
    return json.loads(stdout)


def fetch_milestone_prs(milestone: str, state: str) -> list[dict]:
    """Fetch all PRs in a milestone via ``gh.py prs --milestone``."""
    stdout = run_gh_py("prs", "--milestone", milestone, "--state", state)
    return json.loads(stdout)


def extract_version_section(content: str, milestone: str) -> str:
    """Return the changelog body of a ``## <VERSION>`` section (or "")."""
    match = re.search(
        rf"^## {re.escape(milestone)}(?:\s*\([^)]*\))?\n(.*?)(?=^## |\Z)",
        content,
        re.DOTALL | re.MULTILINE,
    )
    return match.group(1) if match else ""


def extract_subsection(section: str, heading_re: str) -> str:
    """Return the body of a ``### <heading>`` subsection (or "")."""
    match = re.search(rf"^{heading_re}", section, re.MULTILINE)
    if not match:
        return ""
    body = section[match.end():]
    return re.split(r"(?m)^#{2,3}\s", body)[0]


def collect_changelog_prs(section: str) -> set[int]:
    """Collect all PR numbers referenced in a changelog section."""
    numbers = set()
    for num in re.findall(
        r"\[#(\d+)\]\(https://github\.com/penpot/penpot/pull/\d+\)", section
    ):
        numbers.add(int(num))
    for num in re.findall(r"PR:\[(\d+)\]", section):
        numbers.add(int(num))
    return numbers


def collect_changelog_issues(section: str) -> set[int]:
    """Collect all issue numbers referenced in a changelog section."""
    numbers = set()
    for num in re.findall(
        r"\[#(\d+)\]\(https://github\.com/penpot/penpot/issues/\d+\)", section
    ):
        numbers.add(int(num))
    for num in re.findall(r"\[Github #(\d+)\]", section):
        numbers.add(int(num))
    return numbers


# ─────────────────────────────────────────────
#  Subcommand: check-merged
# ─────────────────────────────────────────────


def cmd_check_merged(args: argparse.Namespace) -> None:
    """Warn about any given PR that is not merged. Exits 1 when found."""
    pr_numbers = read_pr_numbers(args)
    if not pr_numbers:
        print(
            "ERROR: no PR numbers provided (pass numbers, --file, or --stdin)",
            file=sys.stderr,
        )
        sys.exit(1)

    prs = fetch_prs_by_numbers(pr_numbers)
    bad = 0
    for pr in prs:
        number = pr.get("number")
        state = pr.get("state")
        if state != "MERGED":
            bad += 1
            print(f"WARNING: #{number} is {state} (not merged)")

    if bad:
        print(f"{bad} of {len(prs)} PRs are NOT merged", file=sys.stderr)
        sys.exit(1)
    print(f"OK: all {len(prs)} PRs are merged", file=sys.stderr)


# ─────────────────────────────────────────────
#  Subcommand: cross-ref
# ─────────────────────────────────────────────


def cmd_cross_ref(args: argparse.Namespace) -> None:
    """Compare merged milestone PRs against the changelog section."""
    with open(args.changes) as f:
        content = f.read()
    section = extract_version_section(content, args.milestone)
    if not section:
        print(
            f'ERROR: no "## {args.milestone}" section found in {args.changes}',
            file=sys.stderr,
        )
        sys.exit(1)

    changelog_refs = collect_changelog_prs(section)
    milestone_prs = fetch_milestone_prs(args.milestone, "all")

    merged = {pr["number"] for pr in milestone_prs if pr.get("state") == "MERGED"}
    closed = [pr for pr in milestone_prs if pr.get("state") == "CLOSED"]

    missing = sorted(merged - changelog_refs)
    print(f"Milestone merged PRs: {len(merged)}")
    print(f"Changelog referenced PRs: {len(changelog_refs)}")
    print(f"PRs in milestone but NOT in changelog: {len(missing)}")
    for num in missing:
        pr = next(p for p in milestone_prs if p["number"] == num)
        print(f"  #{num} {pr['title'][:80]}")

    if closed:
        print("WARNING: CLOSED (unmerged) PRs in milestone:")
        for pr in closed:
            print(f"  #{pr['number']} {pr['title'][:80]}")


# ─────────────────────────────────────────────
#  Subcommand: report
# ─────────────────────────────────────────────


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


def cmd_report(args: argparse.Namespace) -> None:
    """Generate the changelog anomaly report and write it to a file."""
    milestone = args.milestone

    all_issues = json.loads(run_gh_py("issues", milestone, "--state", "all"))
    issue_by_num = {i["number"]: i for i in all_issues}

    all_prs = fetch_milestone_prs(milestone, "all")
    pr_by_num = {p["number"]: p for p in all_prs}

    with open(args.changes) as f:
        content = f.read()
    section = extract_version_section(content, milestone)
    changelog_issues = collect_changelog_issues(section)
    changelog_prs = collect_changelog_prs(section)

    # PRs and issues returned by milestone queries are KNOWN to be in the
    # milestone. Everything else is resolved in bulk via batched gh.py
    # lookups (one GraphQL call per 50 items, never one call per item).
    pr_milestone_cache = {p["number"]: milestone for p in all_prs}
    issue_milestone_cache = {i["number"]: milestone for i in all_issues}

    unknown_prs = set()
    for issue in issue_by_num.values():
        unknown_prs.update(issue.get("closing_prs", []))
    unknown_prs -= set(pr_milestone_cache)
    if unknown_prs:
        print(
            f"Resolving milestones for {len(unknown_prs)} PRs outside {milestone}...",
            file=sys.stderr,
        )
        for pr in json.loads(run_gh_py("prs", *[str(n) for n in sorted(unknown_prs)])):
            pr_milestone_cache[pr["number"]] = pr.get("milestone")

    unknown_issues = set()
    for pr in pr_by_num.values():
        unknown_issues.update(pr.get("closing_issues", []))
    unknown_issues |= collect_changelog_issues(extract_subsection(section, r"### :boom:"))
    unknown_issues -= set(issue_milestone_cache)
    external_issues: dict[int, dict] = {}
    if unknown_issues:
        print(
            f"Resolving milestones for {len(unknown_issues)} issues outside {milestone}...",
            file=sys.stderr,
        )
        for issue in json.loads(
            run_gh_py("issue", *[str(n) for n in sorted(unknown_issues)])
        ):
            issue_milestone_cache[issue["number"]] = issue.get("milestone")
            if "error" not in issue:
                external_issues[issue["number"]] = issue

    def get_issue_labels(issue_num: int) -> list[str] | None:
        """Return the labels of an issue, or None when they cannot be known."""
        issue = issue_by_num.get(issue_num)
        if issue is not None:
            return issue.get("labels", [])
        issue = external_issues.get(issue_num)
        if issue is not None:
            return issue.get("labels", [])
        return None

    def get_pr_milestone(pr_num: int) -> str | None:
        """Return the milestone title for a PR, or None if unassigned."""
        return pr_milestone_cache.get(pr_num)

    def get_issue_milestone(issue_num: int) -> str | None:
        """Return the milestone title for an issue, or None if unassigned."""
        return issue_milestone_cache.get(issue_num)

    excluded_labels = {"release blocker", "no changelog"}

    def issue_excluded(issue: dict | None) -> bool:
        if not issue:
            return True
        if issue.get("state") != "CLOSED":
            return True
        if issue.get("issue_type") in {"Task"}:
            return True
        if issue.get("project_status") in {"Rejected"}:
            return True
        if excluded_labels & set(issue.get("labels", [])):
            return True
        return False

    # Type A: issue in milestone, referenced PR in different milestone or none
    anomalies_a = []
    for issue_num in sorted(changelog_issues):
        issue = issue_by_num.get(issue_num)
        if not issue:
            continue
        if get_issue_milestone(issue_num) != milestone:
            continue
        for pr_num in issue.get("closing_prs", []):
            pr_ms = get_pr_milestone(pr_num)
            if pr_ms != milestone:
                anomalies_a.append(
                    {
                        "issue": issue_num,
                        "issue_title": issue.get("title", ""),
                        "pr": pr_num,
                        "pr_milestone": pr_ms,
                    }
                )

    # Type B: PR in milestone, the issue it closes is in a different milestone.
    # An issue with NO milestone belongs to another (probably private) project
    # and is NOT an anomaly.
    anomalies_b = []
    for pr_num in sorted(changelog_prs):
        pr = pr_by_num.get(pr_num)
        if not pr:
            continue
        if get_pr_milestone(pr_num) != milestone:
            continue
        for issue_num in pr.get("closing_issues", []):
            issue_ms = get_issue_milestone(issue_num)
            if issue_ms is None:
                continue
            if issue_ms != milestone:
                anomalies_b.append(
                    {
                        "pr": pr_num,
                        "pr_title": pr.get("title", ""),
                        "issue": issue_num,
                        "issue_milestone": issue_ms,
                    }
                )

    # Type E: :boom: entry whose issue lacks the `breaking change` label.
    # These stay in the changelog (they are preserved, not removed) — the
    # human decides whether to label the issue or move the entry elsewhere.
    anomalies_e = []
    boom_body = extract_subsection(section, r"### :boom:")
    if boom_body:
        for issue_num in sorted(collect_changelog_issues(boom_body)):
            labels = get_issue_labels(issue_num)
            if labels is None:
                continue
            if "breaking change" not in labels:
                issue = issue_by_num.get(issue_num) or external_issues.get(issue_num) or {}
                anomalies_e.append(
                    {
                        "issue": issue_num,
                        "issue_title": issue.get("title", ""),
                    }
                )

    # Type C: released X.Y.0 sections without a :rocket: subsection.
    # Patches (X.Y.Z with Z != 0) never carry :rocket: — only minors/majors.
    anomalies_c = []
    rocket_heading_re = re.compile(r"^### :rocket:", re.MULTILINE)
    version_sections = re.split(
        r"(?=^## \d+\.\d+\.\d+)", content, flags=re.MULTILINE
    )
    for vs in version_sections:
        m = re.match(r"^## (\d+\.\d+\.\d+)(.*)", vs)
        if not m:
            continue
        ver, suffix = m.group(1), m.group(2)
        if "unreleased" in suffix.lower():
            continue
        if ver.split(".")[2] != "0":
            continue
        if not rocket_heading_re.search(vs):
            anomalies_c.append(ver)

    # Type D: :rocket: entries without issue AND PR references.
    anomalies_d = []
    issue_ref_re = re.compile(
        r"\[#\d+\]\(https://github\.com/penpot/penpot/issues/\d+\)"
    )
    pr_ref_re = re.compile(
        r"\(PR:\s*\[#\d+\]\(https://github\.com/penpot/penpot/pull/\d+\)"
        r"(\s*,\s*\[#\d+\]\(https://github\.com/penpot/penpot/pull/\d+\))*\)"
    )
    for vs in version_sections:
        m = re.match(r"^## (\d+\.\d+\.\d+)(.*)", vs)
        if not m:
            continue
        ver = m.group(1)
        rocket_body = extract_subsection(vs, r"### :rocket:")
        if not rocket_body:
            continue
        for line in rocket_body.splitlines():
            line = line.strip()
            if line.startswith("- ") and not (
                issue_ref_re.search(line) and pr_ref_re.search(line)
            ):
                anomalies_d.append({"version": ver, "line": line[:100]})

    def fmt_ms(ms: str | None) -> str:
        return ms if ms else "_none_"

    with open(args.output, "w") as f:
        f.write(f"# Changelog Anomaly Report — {milestone}\n\n")
        f.write(
            f"Generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}\n\n"
        )
        f.write("---\n\n")

        n_a, n_b, n_c, n_d, n_e = (
            len(anomalies_a),
            len(anomalies_b),
            len(anomalies_c),
            len(anomalies_d),
            len(anomalies_e),
        )

        f.write("## Summary\n\n")
        f.write(
            f"- **Issue in {milestone}, referenced PR in different milestone or no milestone:** {n_a}\n"
        )
        f.write(
            f"- **PR in {milestone}, closing issue in a different milestone:** {n_b}\n"
        )
        f.write(f"- **:boom: entry without breaking change label:** {n_e}\n")
        f.write(f"- **Total anomalies:** {n_a + n_b + n_e}\n")
        f.write(
            f"- **Released X.Y.0 version missing :rocket: section (gap):** {n_c}\n"
        )
        f.write(f"- **:rocket: entry without issue AND PR references (gap):** {n_d}\n\n")

        if n_a or n_b or n_e:
            f.write("## Anomalies\n\n")
            f.write(
                "Types A and B are milestone mismatches between an issue in the changelog "
                "and its referenced PR (or vice-versa). The changelog claim "
                '"this issue is fixed by this PR, all in this milestone" is '
                "inconsistent with the actual milestone assignments. "
                "Resolve by either updating the milestone on the issue/PR or "
                "removing the misleading entry from the changelog.\n\n"
                "Type E entries stay in the changelog: either label the issue "
                "as `breaking change` or move the entry out of `:boom:`.\n\n"
            )

            if n_a:
                f.write(
                    f"### Issue in {milestone}, PR in different milestone or no milestone\n\n"
                )
                by_issue: dict[int, list[dict]] = {}
                for a in anomalies_a:
                    by_issue.setdefault(a["issue"], []).append(a)
                for issue_num in sorted(by_issue):
                    entries = by_issue[issue_num]
                    title = entries[0]["issue_title"]
                    f.write(f"- {issue_link_title(issue_num, title[:80])}\n")
                    for e in entries:
                        ms_label = fmt_ms(e["pr_milestone"])
                        badge = "🔴" if e["pr_milestone"] is None else "⚠️"
                        f.write(
                            f"  - {badge} Referenced {pr_link(e['pr'])} is in milestone **{ms_label}** (expected: {milestone})\n"
                        )
                    f.write("\n")

            if n_b:
                f.write(
                    f"\n### PR in {milestone}, closing issue in a different milestone\n\n"
                )
                by_pr: dict[int, list[dict]] = {}
                for b in anomalies_b:
                    by_pr.setdefault(b["pr"], []).append(b)
                for pr_num in sorted(by_pr):
                    entries = by_pr[pr_num]
                    title = entries[0]["pr_title"]
                    f.write(f"- {pr_link_title(pr_num, title[:80])}\n")
                    for e in entries:
                        ms_label = fmt_ms(e["issue_milestone"])
                        badge = "🔴" if e["issue_milestone"] is None else "⚠️"
                        f.write(
                            f"  - {badge} Closing {issue_link(e['issue'])} is in milestone **{ms_label}** (expected: {milestone})\n"
                        )
                    f.write("\n")

            if n_e:
                f.write("\n### :boom: entry without breaking change label\n\n")
                f.write(
                    "These entries sit under `### :boom: Breaking changes & Deprecations` "
                    "but their issue has no `breaking change` label. They are kept "
                    "in the changelog — add the label on the issue or move the "
                    "entry to its regular section.\n\n"
                )
                for e in anomalies_e:
                    f.write(f"- ⚠️ {issue_link_title(e['issue'], e['issue_title'][:80])}\n")
                f.write("\n")
        else:
            f.write(
                "✅ No anomalies found. All (issue, PR) pairs in the changelog have aligned milestone assignments.\n\n"
            )

        if n_c or n_d:
            f.write("## Highlight gaps\n\n")
            f.write(
                "These are warnings, not anomalies: they do not affect the "
                "milestone-mismatch total above. They track `:rocket:` coverage "
                "across all released X.Y.0 versions. Historical entries (e.g. "
                "Taiga links) predate the current reference convention and are "
                "expected to appear here.\n\n"
            )

            if n_c:
                f.write("### Released X.Y.0 version missing :rocket: section\n\n")
                f.write(
                    "These released minors/majors have no `### :rocket: Epics and highlights` subsection. "
                    "Add highlights to help self-hosted users understand what they are missing.\n\n"
                )
                for ver in anomalies_c:
                    f.write(f"- Version **{ver}**\n")
                f.write("\n")

            if n_d:
                f.write("### :rocket: entry without issue AND PR references\n\n")
                f.write(
                    "These highlight entries lack the required issue AND PR references. "
                    "Add `[#ISSUE](...)` and `(PR: [#PR](...))` links.\n\n"
                )
                for d in anomalies_d:
                    f.write(f"- **{d['version']}**: `{d['line']}`\n")
                f.write("\n")
        elif not (n_a or n_b or n_e):
            f.write(
                "✅ No highlight gaps found. All released X.Y.0 versions have properly referenced :rocket: entries.\n\n"
            )

        f.write("---\n\n")
        f.write("## Context\n\n")
        f.write(f"- Milestone: **{milestone}**\n")
        f.write(f"- Milestone total issues (all states): {len(all_issues)}\n")
        f.write(
            f"- Closed issues in milestone: {sum(1 for i in all_issues if i.get('state') == 'CLOSED')}\n"
        )
        f.write(
            f"- Valid issues after exclusions: {len([i for i in all_issues if not issue_excluded(i)])}\n"
        )
        f.write(f"- Issues referenced in changelog: {len(changelog_issues)}\n")
        f.write(f"- PRs referenced in changelog: {len(changelog_prs)}\n")

    print(f"Anomaly report written to {args.output}")


# ─────────────────────────────────────────────
#  CLI entrypoint
# ─────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Helper commands for the update-changelog skill"
    )
    sub = parser.add_subparsers(dest="command", required=True, title="subcommands")

    p_check = sub.add_parser(
        "check-merged", help="Verify that every given PR is merged"
    )
    p_check.add_argument("numbers", type=int, nargs="*",
                         help="PR numbers to check (space-separated)")
    p_check.add_argument("--file", type=str,
                         help="File with one PR number per line")
    p_check.add_argument("--stdin", action="store_true",
                         help="Read PR numbers from stdin (one per line)")
    p_check.set_defaults(func=cmd_check_merged)

    p_cross = sub.add_parser(
        "cross-ref",
        help="Compare merged milestone PRs against the changelog section",
    )
    p_cross.add_argument("milestone", help="Milestone title (e.g. '2.16.0')")
    p_cross.add_argument("--changes", default="CHANGES.md",
                         help="Path to the changelog file (default: CHANGES.md)")
    p_cross.set_defaults(func=cmd_cross_ref)

    p_report = sub.add_parser(
        "report", help="Generate the anomaly report (CHANGES-ISSUES.md)"
    )
    p_report.add_argument("milestone", help="Milestone title (e.g. '2.16.0')")
    p_report.add_argument("--changes", default="CHANGES.md",
                          help="Path to the changelog file (default: CHANGES.md)")
    p_report.add_argument("--output", default="CHANGES-ISSUES.md",
                          help="Report output path (default: CHANGES-ISSUES.md)")
    p_report.set_defaults(func=cmd_report)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
