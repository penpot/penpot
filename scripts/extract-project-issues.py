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


def fetch_prs_batch(
    pr_list: list[tuple[str, str, int]],
) -> dict[int, dict]:
    """Fetch author and state for multiple PRs in a **single** GraphQL query.

    Args:
        pr_list: List of ``(owner, repo, number)`` tuples.

    Returns:
        Dict mapping PR number -> {author, state}.  PRs that don't exist or
        can't be read are omitted.
    """
    if not pr_list:
        return {}

    # Deduplicate by number (assumes same repo)
    seen: dict[int, tuple[str, str]] = {}
    for owner, repo, number in pr_list:
        if number not in seen:
            seen[number] = (owner, repo)

    # Build aliased query — one alias per PR
    aliases: list[str] = []
    alias_to_number: dict[str, int] = {}
    for number, (owner, repo) in seen.items():
        alias = f"pr_{number}"
        aliases.append(
            f'{alias}: repository(owner: "{owner}", name: "{repo}")'
            f"{{ pullRequest(number: {number})"
            f"{{ number state author{{ login }} }} }}"
        )
        alias_to_number[alias] = number

    query = "query { " + " ".join(aliases) + " }"

    output = run_gh(["api", "graphql", "-f", f"query={query}"])
    data = json.loads(output)
    nodes = data.get("data", {})

    result: dict[int, dict] = {}
    for alias, number in alias_to_number.items():
        pr_node = nodes.get(alias, {}).get("pullRequest")
        if pr_node is None:
            continue
        result[number] = {
            "author": pr_node.get("author", {}).get("login", "unknown"),
            "state": pr_node.get("state", "unknown"),
        }
    return result


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

    # First pass: filter items and collect all linked PR URLs
    matched: list[dict[str, Any]] = []
    all_pr_refs: list[tuple[str, str, int]] = []  # (owner, repo, number)

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

        # Collect linked PR references (resolve URLs now, batch-lookup later)
        pr_refs: list[tuple[str, str, int]] = []
        for pr_url in (item.get("linked pull requests") or []):
            parsed = parse_pr_url(pr_url)
            if parsed:
                pr_refs.append(parsed)
                all_pr_refs.append(parsed)

        matched.append({
            "content": content,
            "labels": item.get("labels", []),
            "assignees": item.get("assignees", []),
            "pr_refs": pr_refs,
            "pr_urls": item.get("linked pull requests") or [],
        })

    # Second pass: batch-resolve all linked PRs (author + state) in one call
    pr_info_map = fetch_prs_batch(all_pr_refs)

    # Third pass: build final issues with only MERGED PRs
    issues: list[dict[str, Any]] = []
    for item in matched:
        content = item["content"]
        linked_prs: list[dict[str, Any]] = []
        for pr_url, (owner, repo, number) in zip(item["pr_urls"], item["pr_refs"]):
            info = pr_info_map.get(number)
            if info is None or info["state"] != "MERGED":
                continue
            linked_prs.append({
                "number": number,
                "author": info["author"],
                "url": pr_url,
            })

        issues.append({
            "number": content.get("number"),
            "title": content.get("title"),
            "url": content.get("url"),
            "labels": item["labels"],
            "assignees": item["assignees"],
            "linked_prs": linked_prs,
        })

    issues.sort(key=lambda x: x["number"])
    return issues


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
