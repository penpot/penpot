#!/usr/bin/env python3
"""
gh.py — Multi-purpose CLI helper for penpot/penpot GitHub operations.

Uses GitHub GraphQL and REST APIs via the authenticated ``gh`` CLI.

Subcommands:
  issues   List issues in a milestone
  prs      Fetch details for one or more PRs (by number or milestone)

Usage:
  python3 tools/gh.py issues <milestone-title>          (default: state=closed)
  python3 tools/gh.py issues "2.16.0" --state all
  python3 tools/gh.py issues "2.16.0" --exclude "release blocker,no changelog"
  python3 tools/gh.py issues "2.16.0" --compare CHANGES.md
  python3 tools/gh.py prs 9179 9204 9311
  python3 tools/gh.py prs --file prs.txt
  cat prs.txt | python3 tools/gh.py prs --stdin
  python3 tools/gh.py prs --milestone "2.16.0"          (default: state=merged)
  python3 tools/gh.py prs --milestone "2.16.0" --state all

Prerequisites:
  - gh CLI authenticated (gh auth status)
  - Python 3.8+
"""

import argparse
import json
import re
import subprocess
import sys
from typing import Any


REPO = "penpot/penpot"
OWNER = "penpot"
REPO_NAME = "penpot"


# ─────────────────────────────────────────────
#  Shared helpers
# ─────────────────────────────────────────────


def run_gh_graphql(query: str, variables: dict) -> Any:
    """Run a GraphQL query via ``gh api graphql --input -``."""
    payload = json.dumps({"query": query, "variables": variables})
    cmd = ["gh", "api", "graphql", "--input", "-"]
    result = subprocess.run(cmd, input=payload, capture_output=True, text=True)
    if result.returncode != 0:
        print(f"gh error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    body = json.loads(result.stdout)
    if "errors" in body:
        for err in body["errors"]:
            print(f"GraphQL error: {err.get('message')}", file=sys.stderr)
        sys.exit(1)
    return body["data"]


# ─────────────────────────────────────────────
#  Shared: milestone lookup
# ─────────────────────────────────────────────

GQL_FIND_MILESTONE_QUERY = """\
query($owner: String!, $repo: String!, $title: String!) {
  repository(owner: $owner, name: $repo) {
    milestones(query: $title, first: 20, states: [OPEN CLOSED]) {
      nodes {
        number
        title
        state
        issues(states: [OPEN]) { totalCount }
        closed_issues: issues(states: [CLOSED]) { totalCount }
      }
    }
  }
}
"""


def find_milestone(title: str) -> dict:
    """Look up milestone by title via GraphQL, return {number, title, open_issues, closed_issues}."""
    variables = {"owner": OWNER, "repo": REPO_NAME, "title": title}
    data = run_gh_graphql(GQL_FIND_MILESTONE_QUERY, variables)
    nodes = data["repository"]["milestones"]["nodes"]
    for ms in nodes:
        if ms["title"] == title:
            return {
                "number": ms["number"],
                "title": ms["title"],
                "open_issues": ms["issues"]["totalCount"],
                "closed_issues": ms["closed_issues"]["totalCount"],
            }
    print(f"ERROR: Milestone \"{title}\" not found in {REPO}", file=sys.stderr)
    sys.exit(1)


# ─────────────────────────────────────────────
#  Subcommand: issues
# ─────────────────────────────────────────────

GQL_ISSUES_QUERY = """\
query($owner: String!, $repo: String!, $milestone: Int!, $cursor: String) {
  repository(owner: $owner, name: $repo) {
    milestone(number: $milestone) {
      issues(first: 100, after: $cursor, states: __STATES__) {
        totalCount
        pageInfo { hasNextPage endCursor }
          nodes {
            ... on Issue {
              number
              title
              state
              issueType { name }
              labels(first: 20) { nodes { name } }
              closedByPullRequestsReferences(first: 5) { nodes { number } }
              projectItems(first: 10) {
                nodes {
                  project { title }
                  fieldValueByName(name: "Status") {
                    ... on ProjectV2ItemFieldSingleSelectValue {
                      name
                    }
                  }
                }
              }
            }
        }
      }
    }
  }
}
"""


def fetch_milestone_issues(milestone_num: int, states: str) -> list[dict]:
    """
    Fetch all issues in a milestone via paginated GraphQL.

    Args:
        milestone_num: milestone number
        states: GraphQL states enum array literal, e.g. ``"[CLOSED]"`` or ``"[OPEN CLOSED]"``

    Returns:
        List of {number, title, state, issue_type: str|None, labels: [str], closing_prs: [int]}
    """
    query = GQL_ISSUES_QUERY.replace("__STATES__", states)
    all_nodes: list[dict] = []
    cursor: str | None = None

    while True:
        variables: dict[str, Any] = {
            "owner": OWNER,
            "repo": REPO_NAME,
            "milestone": milestone_num,
            "cursor": cursor,
        }
        data = run_gh_graphql(query, variables)
        issues = data["repository"]["milestone"]["issues"]
        page_info = issues["pageInfo"]

        for node in issues["nodes"]:
            if node is None:
                continue
            issue_type = node.get("issueType")
            # Extract project status from the "Main" project board (if present)
            project_status = None
            for pi in (node.get("projectItems") or {}).get("nodes") or []:
                project = pi.get("project") or {}
                if project.get("title") == "Main":
                    status_field = pi.get("fieldValueByName") or {}
                    project_status = status_field.get("name")
                    break
            all_nodes.append({
                "number": node["number"],
                "title": node["title"],
                "state": node["state"],
                "issue_type": issue_type["name"] if issue_type else None,
                "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
                "closing_prs": [pr["number"] for pr in node["closedByPullRequestsReferences"]["nodes"]],
                "project_status": project_status,
            })

        total = len(all_nodes)
        print(f"  ... fetched {total} issues so far", file=sys.stderr)

        if not page_info["hasNextPage"]:
            break
        cursor = page_info["endCursor"]

    return all_nodes


def load_existing_issue_numbers(filepath: str) -> set[int]:
    """Parse all ``#NNNN`` references from a file (e.g. CHANGES.md)."""
    pattern = re.compile(r"#(\d{3,5})\b")
    nums: set[int] = set()
    with open(filepath) as f:
        for line in f:
            for m in pattern.finditer(line):
                nums.add(int(m.group(1)))
    return nums


def cmd_issues(args: argparse.Namespace) -> None:
    """Handle the ``issues`` subcommand."""

    # Resolve milestone
    print(f"Looking up milestone \"{args.milestone}\"...", file=sys.stderr)
    ms = find_milestone(args.milestone)
    print(f"Milestone #{ms['number']}: {ms['open_issues']} open, {ms['closed_issues']} closed",
          file=sys.stderr)

    # Map state to GraphQL enum array literal
    state_map = {"open": "[OPEN]", "closed": "[CLOSED]", "all": "[OPEN CLOSED]"}
    gql_states = state_map[args.state]

    # Fetch issues
    print(f"Fetching {args.state} issues via GraphQL...", file=sys.stderr)
    issues = fetch_milestone_issues(ms["number"], gql_states)
    print(f"Fetched {len(issues)} issues total", file=sys.stderr)

    # Filter by excluded labels
    if args.exclude:
        exclusions = set(label.strip() for label in args.exclude.split(","))
        filtered = [issue for issue in issues
                    if not any(lbl in exclusions for lbl in issue["labels"])]
        print(f"After excluding labels: {len(filtered)} issues", file=sys.stderr)
        issues = filtered

    # Filter out issues with "Rejected" project status (unless --include-rejected)
    if not args.include_rejected:
        rejected = [iss for iss in issues if iss.get("project_status") == "Rejected"]
        if rejected:
            issues = [iss for iss in issues if iss.get("project_status") != "Rejected"]
            print(f"After excluding rejected: {len(issues)} issues (removed {len(rejected)}: {[r['number'] for r in rejected]})", file=sys.stderr)

    # Filter to issues NOT yet in the comparison file (if --compare given)
    if args.compare:
        existing_nums = load_existing_issue_numbers(args.compare)
        missing = [iss for iss in issues if iss["number"] not in existing_nums]
        missing.sort(key=lambda x: x["number"])
        print(f"Issues not yet in changelog: {len(missing)}", file=sys.stderr)
        issues = missing

    print(json.dumps(issues, indent=2))


# ─────────────────────────────────────────────
#  Subcommand: prs
# ─────────────────────────────────────────────

PRS_BATCH_SIZE = 50

GQL_PRS_QUERY_ITEM = """\
    pr_{num}: pullRequest(number: {num}) {{
      number
      title
      body
      state
      mergedAt
      createdAt
      author {{ login }}
      labels(first: 20) {{ nodes {{ name }} }}
      closingIssuesReferences(first: 5) {{ nodes {{ number }} }}
    }}
"""

GQL_PRS_QUERY_WRAPPER = """\
query($owner: String!, $repo: String!) {{
  repository(owner: $owner, name: $repo) {{
{items}
  }}
}}
"""


def fetch_prs_batch(pr_numbers: list[int]) -> list[dict]:
    """
    Fetch details for a list of PR numbers in a single GraphQL query.

    Uses numbered aliases (pr_1234, pr_5678, …) so each PR is looked up by
    number in one round-trip. Returns entries in the same order as the input.
    """
    items = "\n".join(
        GQL_PRS_QUERY_ITEM.format(num=n) for n in pr_numbers
    )
    query = GQL_PRS_QUERY_WRAPPER.format(items=items)
    variables = {"owner": OWNER, "repo": REPO_NAME}

    data = run_gh_graphql(query, variables)
    repo = data["repository"]

    results: list[dict] = []
    for num in pr_numbers:
        pr = repo.get(f"pr_{num}")
        if pr is None:
            results.append({
                "number": num,
                "error": "not_found",
            })
            continue
        results.append({
            "number": pr["number"],
            "title": pr["title"],
            "body": pr.get("body"),
            "state": pr["state"],
            "merged_at": pr.get("mergedAt"),
            "created_at": pr.get("createdAt"),
            "author": pr["author"]["login"] if pr["author"] else None,
            "labels": [lbl["name"] for lbl in pr["labels"]["nodes"]],
            "closing_issues": [iss["number"] for iss in pr["closingIssuesReferences"]["nodes"]],
        })
    return results


GQL_MILESTONE_PRS_QUERY = """\
query($owner: String!, $repo: String!, $milestone: Int!, $cursor: String) {
  repository(owner: $owner, name: $repo) {
    milestone(number: $milestone) {
      pullRequests(first: 100, after: $cursor, states: __STATES__) {
        totalCount
        pageInfo { hasNextPage endCursor }
        nodes {
          ... on PullRequest {
            number
            title
            body
            state
            mergedAt
            createdAt
            author { login }
            labels(first: 20) { nodes { name } }
            closingIssuesReferences(first: 5) { nodes { number } }
          }
        }
      }
    }
  }
}
"""


def fetch_milestone_prs(milestone_num: int, states: str) -> list[dict]:
    """
    Fetch all pull requests in a milestone via paginated GraphQL.

    Args:
        milestone_num: milestone number
        states: GraphQL states enum array literal, e.g. ``"[MERGED]"`` or ``"[OPEN CLOSED MERGED]"``

    Returns:
        List of {number, title, body, state, merged_at, created_at, author,
                labels: [str], closing_issues: [int]}
    """
    query = GQL_MILESTONE_PRS_QUERY.replace("__STATES__", states)
    all_nodes: list[dict] = []
    cursor: str | None = None

    while True:
        variables: dict[str, Any] = {
            "owner": OWNER,
            "repo": REPO_NAME,
            "milestone": milestone_num,
            "cursor": cursor,
        }
        data = run_gh_graphql(query, variables)
        prs = data["repository"]["milestone"]["pullRequests"]
        page_info = prs["pageInfo"]

        for node in prs["nodes"]:
            if node is None:
                continue
            all_nodes.append({
                "number": node["number"],
                "title": node["title"],
                "body": node.get("body"),
                "state": node["state"],
                "merged_at": node.get("mergedAt"),
                "created_at": node.get("createdAt"),
                "author": node["author"]["login"] if node["author"] else None,
                "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
                "closing_issues": [iss["number"] for iss in node["closingIssuesReferences"]["nodes"]],
            })

        total = len(all_nodes)
        print(f"  ... fetched {total} PRs so far", file=sys.stderr)

        if not page_info["hasNextPage"]:
            break
        cursor = page_info["endCursor"]

    return all_nodes


def cmd_prs(args: argparse.Namespace) -> None:
    """Handle the ``prs`` subcommand."""

    # ── Milestone path ──────────────────────────────────────────────
    if args.milestone:
        print(f"Looking up milestone \"{args.milestone}\"...", file=sys.stderr)
        ms = find_milestone(args.milestone)

        state_map = {"open": "[OPEN]", "closed": "[CLOSED]", "merged": "[MERGED]", "all": "[OPEN CLOSED MERGED]"}
        gql_states = state_map[args.state]

        print(f"Fetching {args.state} PRs via GraphQL...", file=sys.stderr)
        prs = fetch_milestone_prs(ms["number"], gql_states)
        print(f"Fetched {len(prs)} PRs total", file=sys.stderr)
        print(json.dumps(prs, indent=2))
        return

    # ── Number-based path ───────────────────────────────────────────
    pr_numbers: list[int] = []

    if args.numbers:
        pr_numbers.extend(args.numbers)

    if args.file:
        with open(args.file) as f:
            for line in f:
                line = line.strip()
                if line:
                    pr_numbers.append(int(line))

    if args.stdin:
        for line in sys.stdin:
            line = line.strip()
            if line:
                pr_numbers.append(int(line))

    if not pr_numbers:
        print("ERROR: no PR numbers provided (pass numbers, --file, --stdin, or --milestone)",
              file=sys.stderr)
        sys.exit(1)

    # Deduplicate while preserving order
    seen: set[int] = set()
    pr_numbers = [n for n in pr_numbers if not (n in seen or seen.add(n))]

    print(f"Fetching {len(pr_numbers)} PRs in batches of {PRS_BATCH_SIZE}...",
          file=sys.stderr)

    all_results: list[dict] = []
    for i in range(0, len(pr_numbers), PRS_BATCH_SIZE):
        batch = pr_numbers[i : i + PRS_BATCH_SIZE]
        print(f"  batch {i // PRS_BATCH_SIZE + 1}: PRs {batch[0]}..{batch[-1]}",
              file=sys.stderr)
        all_results.extend(fetch_prs_batch(batch))

    print(json.dumps(all_results, indent=2))


# ─────────────────────────────────────────────
#  CLI entrypoint
# ─────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Multi-purpose CLI helper for penpot/penpot GitHub operations"
    )
    sub = parser.add_subparsers(dest="command", required=True, title="subcommands")

    # --- issues ---
    p_issues = sub.add_parser("issues", help="List issues in a milestone")
    p_issues.add_argument("milestone", help="Milestone title, e.g. '2.16.0'")
    p_issues.add_argument(
        "--state", choices=["open", "closed", "all"], default="closed",
        help="Issue state filter (default: closed)"
    )
    p_issues.add_argument(
        "--exclude", "--exclude-labels",
        help="Comma-separated labels to exclude, e.g. 'release blocker,no changelog'"
    )
    p_issues.add_argument(
        "--compare",
        help="Path to CHANGES.md; only show issues NOT yet referenced in that file"
    )
    p_issues.add_argument(
        "--include-rejected", action="store_true",
        help="Include issues with 'Rejected' project status (excluded by default)"
    )
    p_issues.set_defaults(func=cmd_issues)

    # --- prs ---
    p_prs = sub.add_parser("prs", help="Fetch details for one or more PRs (by number or milestone)")
    p_prs.add_argument(
        "numbers", type=int, nargs="*",
        help="PR numbers to fetch (space-separated)"
    )
    p_prs.add_argument(
        "--file", type=str,
        help="File with one PR number per line"
    )
    p_prs.add_argument(
        "--stdin", action="store_true",
        help="Read PR numbers from stdin (one per line)"
    )
    p_prs.add_argument(
        "--milestone", type=str,
        help="Milestone title, e.g. '2.16.0' (fetches all PRs in the milestone)"
    )
    p_prs.add_argument(
        "--state", choices=["open", "closed", "merged", "all"], default="merged",
        help="PR state filter when using --milestone (default: merged)"
    )
    p_prs.set_defaults(func=cmd_prs)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
