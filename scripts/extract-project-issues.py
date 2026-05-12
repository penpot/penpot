#!/usr/bin/env python3
"""
Extract issues from a GitHub Project board filtered by milestone and status.

Outputs structured JSON (default) or a human/LLM-friendly Markdown list.
Each issue includes its labels, assignees, and linked PRs (number + author),
which is useful for changelog generation.

Requires: `gh` CLI authenticated against the repository owner.
"""

import argparse
import json
import subprocess
import sys
from typing import Any


def run_gh(args: list[str]) -> str:
    """Run a `gh` command and return stdout. Exits on failure."""
    cmd = ["gh"] + args
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
        return result.stdout
    except subprocess.CalledProcessError as e:
        print(f"Error running: gh {' '.join(args)}", file=sys.stderr)
        print(e.stderr, file=sys.stderr)
        sys.exit(1)
    except FileNotFoundError:
        print("Error: `gh` CLI not found. Please install GitHub CLI.", file=sys.stderr)
        sys.exit(1)


def resolve_project_number(owner: str, project_ref: str) -> int:
    """Resolve a project number from a title or return it directly if numeric."""
    if project_ref.isdigit():
        return int(project_ref)

    # Look up project by title
    output = run_gh(["project", "list", "--owner", owner, "--format", "json"])
    data = json.loads(output)
    for proj in data.get("projects", []):
        if proj["title"].lower() == project_ref.lower():
            return proj["number"]

    available = [p["title"] for p in data.get("projects", [])]
    print(
        f"Error: project '{project_ref}' not found. Available: {available}",
        file=sys.stderr,
    )
    sys.exit(1)


def fetch_pr_info(owner: str, repo: str, pr_number: int) -> dict | None:
    """Fetch PR author and state. Returns None if the PR cannot be read."""
    try:
        output = run_gh([
            "pr", "view", str(pr_number),
            "--repo", f"{owner}/{repo}",
            "--json", "author,state",
        ])
    except SystemExit:
        # gh call failed (e.g. PR not found)
        return None

    data = json.loads(output)
    author = data.get("author", {}).get("login", "unknown")
    state = data.get("state", "unknown")
    return {"author": author, "state": state}


def fetch_project_items(owner: str, project_number: int) -> list[dict]:
    """Fetch all items from a GitHub Project."""
    output = run_gh([
        "project", "item-list", str(project_number),
        "--owner", owner,
        "--limit", "300",
        "--format", "json",
    ])
    data = json.loads(output)
    return data.get("items", [])


def parse_pr_url(url: str) -> tuple[str, str, int] | None:
    """Parse a PR URL into (owner, repo, pr_number). Returns None if not a PR URL."""
    # URL format: https://github.com/penpot/penpot/pull/9166
    parts = url.rstrip("/").split("/")
    if len(parts) >= 7 and parts[-2] == "pull" and parts[-1].isdigit():
        return parts[-4], parts[-3], int(parts[-1])
    return None


def collect_issues(args: argparse.Namespace) -> list[dict[str, Any]]:
    """Collect and filter issues from the project board."""
    project_number = resolve_project_number(args.owner, args.project)
    items = fetch_project_items(args.owner, project_number)

    matched: list[dict[str, Any]] = []
    for item in items:
        # Skip items that are themselves PullRequests
        content = item.get("content") or {}
        if content.get("type") != "Issue":
            continue

        # Check milestone
        milestone = item.get("milestone") or {}
        if milestone.get("title") != args.milestone:
            continue

        # Check status
        if item.get("status") != args.status:
            continue

        # Parse linked PRs — only include MERGED ones
        linked_prs_raw = item.get("linked pull requests") or []
        linked_prs: list[dict[str, Any]] = []
        for pr_url in linked_prs_raw:
            parsed = parse_pr_url(pr_url)
            if not parsed:
                continue
            owner, repo, pr_number = parsed
            info = fetch_pr_info(owner, repo, pr_number)
            if info is None or info["state"] != "MERGED":
                continue
            linked_prs.append({
                "number": pr_number,
                "author": info["author"],
                "url": pr_url,
            })

        issue: dict[str, Any] = {
            "number": content.get("number"),
            "title": content.get("title"),
            "url": content.get("url"),
            "labels": item.get("labels", []),
            "assignees": item.get("assignees", []),
            "linked_prs": linked_prs,
        }
        matched.append(issue)

    matched.sort(key=lambda x: x["number"])
    return matched


def output_json(issues: list[dict[str, Any]], args: argparse.Namespace) -> None:
    """Print output as JSON."""
    result = {
        "project": {
            "number": resolve_project_number(args.owner, args.project),
            "title": args.project if not args.project.isdigit() else None,
        },
        "owner": args.owner,
        "milestone": args.milestone,
        "status": args.status,
        "total_issues": len(issues),
        "issues": issues,
    }
    indent = 2 if args.pretty else None
    print(json.dumps(result, indent=indent, ensure_ascii=False))


def output_markdown(issues: list[dict[str, Any]], args: argparse.Namespace) -> None:
    """Print output as a human/LLM-friendly Markdown list."""
    lines: list[str] = []
    lines.append(f"Issues with milestone **{args.milestone}** and status **{args.status}**")
    lines.append(f"(project: {args.project})")
    lines.append("")
    lines.append(f"**Total: {len(issues)} issues**")
    lines.append("")

    for issue in issues:
        num = issue["number"]
        title = issue["title"]
        url = issue["url"]

        lines.append(f"### #{num} — {title}")
        lines.append(f"- URL: {url}")
        if issue["labels"]:
            lines.append(f"- Labels: {', '.join(issue['labels'])}")
        if issue["assignees"]:
            lines.append(f"- Assignees: {', '.join(issue['assignees'])}")
        if issue["linked_prs"]:
            pr_list = [
                f"  - #{pr['number']} by @{pr['author']}"
                for pr in issue["linked_prs"]
            ]
            lines.append("- Linked PRs:")
            lines.extend(pr_list)
        lines.append("")

    print("\n".join(lines))


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Extract issues from a GitHub Project board by milestone.",
    )
    parser.add_argument(
        "--owner", "-o",
        default="penpot",
        help="GitHub owner (user or org). Default: penpot",
    )
    parser.add_argument(
        "--project", "-p",
        default="8",
        help="Project number or title. Default: 8 (Main)",
    )
    parser.add_argument(
        "--milestone", "-m",
        default="2.15.0",
        help="Milestone title to filter by. Default: 2.15.0",
    )
    parser.add_argument(
        "--status", "-s",
        default="Done",
        help="Status field value to filter by. Default: Done",
    )
    parser.add_argument(
        "--repo", "-r",
        default="penpot/penpot",
        help="Repository to fetch PR authors from. Default: penpot/penpot",
    )
    parser.add_argument(
        "--format", "-f",
        choices=["json", "markdown"],
        default="json",
        help="Output format. Default: json",
    )
    parser.add_argument(
        "--pretty", action="store_true",
        help="Pretty-print the JSON output (only with --format json)",
    )
    args = parser.parse_args()

    issues = collect_issues(args)

    if args.format == "markdown":
        output_markdown(issues, args)
    else:
        output_json(issues, args)


if __name__ == "__main__":
    main()


if __name__ == "__main__":
    main()
