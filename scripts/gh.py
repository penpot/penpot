#!/usr/bin/env python3
"""
gh.py — Multi-purpose CLI helper for penpot/penpot GitHub operations.

Uses GitHub GraphQL and REST APIs via the authenticated ``gh`` CLI.

Subcommands:
  issues      List issues in a milestone (or unassigned with milestone=none)
  prs         Fetch details for one or more PRs (by number or milestone)
  advisories  List or inspect GitHub security advisories
  link-issue  Explicitly link a GitHub issue to a pull request

Usage:
  python3 scripts/gh.py issues <milestone-title>            (default: state=closed)
  python3 scripts/gh.py issues "2.16.0" --state all
  python3 scripts/gh.py issues "2.16.0" --exclude "release blocker,no changelog"
  python3 scripts/gh.py issues "2.16.0" --label "bug"       (include only issues with label)
  python3 scripts/gh.py issues "2.16.0" --label "bug,regression" --exclude "no changelog"
  python3 scripts/gh.py issues "2.16.0" --compare CHANGES.md
  python3 scripts/gh.py issues none                         (issues with no milestone)
  python3 scripts/gh.py issues none --label "enhancement"
  python3 scripts/gh.py issues none --state open
  python3 scripts/gh.py prs 9179 9204 9311
  python3 scripts/gh.py prs --file prs.txt
  cat prs.txt | python3 scripts/gh.py prs --stdin
  python3 scripts/gh.py prs --milestone "2.16.0"            (default: state=merged)
  python3 scripts/gh.py prs --milestone "2.16.0" --state all
  python3 scripts/gh.py issue 11235 11236
  python3 scripts/gh.py advisories                          (list all advisories)
  python3 scripts/gh.py advisories --severity critical      (filter by severity)
  python3 scripts/gh.py advisories GHSA-xvj6-fh9w-gjw7     (single advisory detail)
  python3 scripts/gh.py link-issue 11235 11243

Prerequisites:
  - gh CLI authenticated (gh auth status)
  - Python 3.8+
"""

import argparse
import json
import re
import subprocess
import sys
import time
from typing import Any


REPO = "penpot/penpot"
OWNER = "penpot"
REPO_NAME = "penpot"

# Transient gateway timeouts from the GitHub API are retried, nothing else.
GH_RETRIES = 3
GH_RETRY_DELAYS = (5, 15)  # seconds waited before retry N (last delay repeats)


def run_gh_command(cmd: list[str], input_text: str | None = None) -> str:
    """Run a ``gh`` command, retrying transient HTTP 504s.

    Raises `GhCommandFailed` on failure so callers can choose between
    aborting (normal commands) and degrading (batched lookups).
    """
    last_stderr = ""
    for attempt in range(GH_RETRIES):
        if attempt:
            delay = GH_RETRY_DELAYS[min(attempt - 1, len(GH_RETRY_DELAYS) - 1)]
            print(
                f"gh: HTTP 504, retrying in {delay}s"
                f" (attempt {attempt + 1}/{GH_RETRIES})...",
                file=sys.stderr,
            )
            time.sleep(delay)
        result = subprocess.run(
            cmd, input=input_text, capture_output=True, text=True
        )
        if result.returncode == 0:
            return result.stdout
        last_stderr = result.stderr
        if "504" not in result.stderr:
            break
    raise GhCommandFailed(last_stderr)


class GhCommandFailed(Exception):
    """A ``gh`` invocation failed (stderr kept for the caller to judge)."""

    def __init__(self, stderr: str) -> None:
        super().__init__(stderr)
        self.stderr = stderr


def fail_gh(stderr: str) -> None:
    """Report a ``gh`` failure and exit (standard behavior for CLI commands)."""
    print(f"gh error: {stderr}", file=sys.stderr)
    sys.exit(1)


# ─────────────────────────────────────────────
#  Shared helpers
# ─────────────────────────────────────────────


def post_graphql(query: str, variables: dict) -> Any:
    """POST a GraphQL query via ``gh`` and return the raw response body."""
    payload = json.dumps({"query": query, "variables": variables})
    stdout = run_gh_command(["gh", "api", "graphql", "--input", "-"], payload)
    return json.loads(stdout)


def run_gh_graphql(query: str, variables: dict) -> Any:
    """Run a GraphQL query via ``gh api graphql --input -``."""
    try:
        body = post_graphql(query, variables)
    except GhCommandFailed as err:
        fail_gh(err.stderr)
    if "errors" in body:
        for err in body["errors"]:
            print(f"GraphQL error: {err.get('message')}", file=sys.stderr)
        sys.exit(1)
    return body["data"]


def run_gh_rest(path: str) -> Any:
    """Run a REST API call via ``gh api``."""
    try:
        return json.loads(run_gh_command(["gh", "api", path]))
    except GhCommandFailed as err:
        fail_gh(err.stderr)


# ─────────────────────────────────────────────
#  Subcommand: link-issue
# ─────────────────────────────────────────────

GQL_LINK_TARGETS_QUERY = """\
query($owner: String!, $repo: String!, $issueNumber: Int!, $prNumber: Int!) {
  repository(owner: $owner, name: $repo) {
    issue(number: $issueNumber) { id number }
    pullRequest(number: $prNumber) { id number }
  }
}
"""

GQL_ADD_CLOSE_ISSUE_REFERENCES = """\
mutation($issueId: ID!, $pullRequestIds: [ID!]!) {
  addCloseIssueReferences(input: {issueId: $issueId, pullRequestIds: $pullRequestIds}) {
    issue { id number }
  }
}
"""

def link_issue_to_pr(issue_number: int, pr_number: int) -> dict:
    """Add an explicit GitHub issue-to-PR link.

    We trust the successful ``addCloseIssueReferences`` mutation instead of
    re-querying: GitHub does not reliably report mutation-created links
    through ``closedByPullRequestsReferences(userLinkedOnly: true)``.
    """
    if issue_number <= 0 or pr_number <= 0:
        raise ValueError("issue and pull request numbers must be positive")

    variables = {
        "owner": OWNER,
        "repo": REPO_NAME,
        "issueNumber": issue_number,
        "prNumber": pr_number,
    }
    target_data = run_gh_graphql(GQL_LINK_TARGETS_QUERY, variables)
    repository = target_data.get("repository") or {}
    issue = repository.get("issue") or {}
    pull_request = repository.get("pullRequest") or {}
    if not issue.get("id"):
        raise RuntimeError(f"issue #{issue_number} was not found in {REPO}")
    if not pull_request.get("id"):
        raise RuntimeError(f"pull request #{pr_number} was not found in {REPO}")

    mutation_data = run_gh_graphql(
        GQL_ADD_CLOSE_ISSUE_REFERENCES,
        {
            "issueId": issue["id"],
            "pullRequestIds": [pull_request["id"]],
        },
    )
    mutation_result = mutation_data.get("addCloseIssueReferences") or {}
    linked_issue = mutation_result.get("issue") or {}
    if linked_issue.get("number") != issue_number:
        raise RuntimeError(f"GitHub did not link issue #{issue_number}")

    return {
        "linked": True,
        "issue": {"number": issue_number},
        "pull_request": {"number": pr_number},
    }


def cmd_link_issue(args: argparse.Namespace) -> None:
    """Handle the ``link-issue`` subcommand."""
    print(
        f"Linking issue #{args.issue_number} to pull request #{args.pr_number}...",
        file=sys.stderr,
    )
    try:
        result = link_issue_to_pr(args.issue_number, args.pr_number)
    except (ValueError, RuntimeError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)

    print(
        f"Linked issue #{args.issue_number} -> pull request #{args.pr_number}",
        file=sys.stderr,
    )
    print(json.dumps(result, indent=2))


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
              assignees(first: 10) { nodes { login } }
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


GQL_NO_MILESTONE_QUERY = """\
query($query: String!, $cursor: String) {
  search(
    query: $query
    type: ISSUE
    first: 100
    after: $cursor
  ) {
    issueCount
    pageInfo { hasNextPage endCursor }
    nodes {
      ... on Issue {
        number
        title
        state
        milestone { title }
        issueType { name }
        labels(first: 20) { nodes { name } }
        assignees(first: 10) { nodes { login } }
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
"""


def node_assignees(node: dict) -> list[str]:
    """Extract assignee logins from a GraphQL issue/PR node."""
    return [
        a["login"] for a in (node.get("assignees") or {}).get("nodes") or []
    ]


def node_projects(node: dict) -> list[str]:
    """Extract project board titles from a GraphQL issue node."""
    return [
        (pi.get("project") or {}).get("title")
        for pi in (node.get("projectItems") or {}).get("nodes") or []
        if (pi.get("project") or {}).get("title")
    ]


def node_main_status(node: dict) -> str | None:
    """Extract the "Main" project board status from a GraphQL issue node."""
    for pi in (node.get("projectItems") or {}).get("nodes") or []:
        project = pi.get("project") or {}
        if project.get("title") == "Main":
            status_field = pi.get("fieldValueByName") or {}
            return status_field.get("name")
    return None


def fetch_no_milestone_issues(states: str, labels: str | None = None) -> list[dict]:
    """
    Fetch all issues that belong to NO milestone via paginated GraphQL search.

    Args:
        states: GraphQL states enum array literal, e.g. ``"[CLOSED]"`` or ``"[OPEN CLOSED]"``
        labels: optional comma-separated labels to include (built into the search query)

    Returns:
        List of {number, title, state, milestone, issue_type, labels, assignees, closing_prs, project_status, projects}
    """
    all_nodes: list[dict] = []
    cursor: str | None = None

    # Map states enum literal to search qualifiers
    state_qualifiers = {
        "[OPEN]": "is:open",
        "[CLOSED]": "is:closed",
        "[OPEN CLOSED]": "",
    }
    state_q = state_qualifiers.get(states, "")
    label_q = ""
    if labels:
        for lbl in labels.split(","):
            label_q += f" label:\"{lbl.strip()}\""
    search_query = f"repo:{OWNER}/{REPO_NAME} is:issue no:milestone{state_q}{label_q}".strip()
    while True:
        variables: dict[str, Any] = {
            "query": search_query,
            "cursor": cursor,
        }
        data = run_gh_graphql(GQL_NO_MILESTONE_QUERY, variables)
        search = data["search"]
        page_info = search["pageInfo"]

        for node in search["nodes"]:
            if node is None:
                continue
            issue_type = node.get("issueType")
            ms = node.get("milestone")
            all_nodes.append({
                "number": node["number"],
                "title": node["title"],
                "state": node["state"],
                "milestone": ms["title"] if ms else None,
                "issue_type": issue_type["name"] if issue_type else None,
                "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
                "assignees": node_assignees(node),
                "closing_prs": [pr["number"] for pr in node["closedByPullRequestsReferences"]["nodes"]],
                "project_status": node_main_status(node),
                "projects": node_projects(node),
            })

        total = len(all_nodes)
        print(f"  ... fetched {total} issues so far", file=sys.stderr)

        if not page_info["hasNextPage"]:
            break
        cursor = page_info["endCursor"]

    return all_nodes


def fetch_milestone_issues(milestone_num: int, states: str) -> list[dict]:
    """
    Fetch all issues in a milestone via paginated GraphQL.

    Args:
        milestone_num: milestone number
        states: GraphQL states enum array literal, e.g. ``"[CLOSED]"`` or ``"[OPEN CLOSED]"``

    Returns:
        List of {number, title, state, issue_type: str|None, labels: [str], assignees: [str], closing_prs: [int], project_status, projects}
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
            all_nodes.append({
                "number": node["number"],
                "title": node["title"],
                "state": node["state"],
                "issue_type": issue_type["name"] if issue_type else None,
                "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
                "assignees": node_assignees(node),
                "closing_prs": [pr["number"] for pr in node["closedByPullRequestsReferences"]["nodes"]],
                "project_status": node_main_status(node),
                "projects": node_projects(node),
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

    # Map state to GraphQL enum array literal
    state_map = {"open": "[OPEN]", "closed": "[CLOSED]", "all": "[OPEN CLOSED]"}
    gql_states = state_map[args.state]

    # ── No-milestone path ──────────────────────────────────────────
    if args.milestone and args.milestone.lower() == "none":
        print("Fetching issues with NO milestone...", file=sys.stderr)
        issues = fetch_no_milestone_issues(gql_states, labels=args.label)
        print(f"Fetched {len(issues)} issues total", file=sys.stderr)

    # ── Milestone path ─────────────────────────────────────────────
    else:
        print(f"Looking up milestone \"{args.milestone}\"...", file=sys.stderr)
        ms = find_milestone(args.milestone)
        print(f"Milestone #{ms['number']}: {ms['open_issues']} open, {ms['closed_issues']} closed",
              file=sys.stderr)
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

    # Exclude issues with type "Task" (internal chores) — opt out with --include-tasks
    if not args.include_tasks:
        tasks = [iss for iss in issues if iss.get("issue_type") == "Task"]
        if tasks:
            issues = [iss for iss in issues if iss.get("issue_type") != "Task"]
            print(f"After excluding Task issues: {len(issues)} issues (removed {len(tasks)}: {[t['number'] for t in tasks]})", file=sys.stderr)

    # Filter by included labels (--label) — issue must have ALL specified labels
    if args.label:
        inclusions = set(label.strip() for label in args.label.split(","))
        filtered = [issue for issue in issues
                    if all(lbl in issue["labels"] for lbl in inclusions)]
        print(f"After filtering by labels: {len(filtered)} issues", file=sys.stderr)
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
      milestone {{ title }}
      author {{ login }}
      assignees(first: 10) {{ nodes {{ login }} }}
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
            "milestone": (pr.get("milestone") or {}).get("title"),
            "author": pr["author"]["login"] if pr["author"] else None,
            "assignees": node_assignees(pr),
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
            headRefName
            author { login }
            assignees(first: 10) { nodes { login } }
            labels(first: 20) { nodes { name } }
            files(first: 100) { nodes { path } }
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
        List of {number, title, body, state, merged_at, created_at,
                head_ref_name, author, assignees, labels: [str], files: [str],
                closing_issues: [int]}
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
                "head_ref_name": node.get("headRefName"),
                "author": node["author"]["login"] if node["author"] else None,
                "assignees": node_assignees(node),
                "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
                "files": [file["path"] for file in node["files"]["nodes"]],
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
        for pr in prs:
            pr["milestone"] = ms["title"]
        print(f"Fetched {len(prs)} PRs total", file=sys.stderr)
        print(json.dumps(prs, indent=2))
        return

    # ── Number-based path ───────────────────────────────────────────
    pr_numbers = read_numbers_from_args(args)
    if not pr_numbers:
        print("ERROR: no PR numbers provided (pass numbers, --file, --stdin, or --milestone)",
              file=sys.stderr)
        sys.exit(1)

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
#  Subcommand: issue (fetch issues by number, batched)
# ─────────────────────────────────────────────

GQL_ISSUE_BY_NUMBER_QUERY_ITEM = """\
    issue_{num}: issue(number: {num}) {{
      number
      title
      state
      milestone {{ title }}
      issueType {{ name }}
      labels(first: 20) {{ nodes {{ name }} }}
      assignees(first: 10) {{ nodes {{ login }} }}
      closedByPullRequestsReferences(first: 5) {{ nodes {{ number }} }}
      projectItems(first: 10) {{
        nodes {{
          project {{ title }}
          fieldValueByName(name: "Status") {{
            ... on ProjectV2ItemFieldSingleSelectValue {{
              name
            }}
          }}
        }}
      }}
    }}
"""


def issue_node_to_dict(node: dict) -> dict:
    """Convert a GraphQL issue node to the shared issue dict shape."""
    issue_type = node.get("issueType")
    ms = node.get("milestone")
    return {
        "number": node["number"],
        "title": node["title"],
        "state": node["state"],
        "milestone": ms["title"] if ms else None,
        "issue_type": issue_type["name"] if issue_type else None,
        "labels": [lbl["name"] for lbl in node["labels"]["nodes"]],
        "assignees": node_assignees(node),
        "closing_prs": [pr["number"] for pr in node["closedByPullRequestsReferences"]["nodes"]],
        "project_status": node_main_status(node),
        "projects": node_projects(node),
    }


class IssueBatchFailed(Exception):
    """One aliased issue lookup failed; the batch cannot be trusted as a whole."""


def _fetch_issues_batch(issue_numbers: list[int]) -> list[dict]:
    """Single batched issue lookup; raises `IssueBatchFailed` on any failure."""
    items = "\n".join(
        GQL_ISSUE_BY_NUMBER_QUERY_ITEM.format(num=n) for n in issue_numbers
    )
    query = GQL_PRS_QUERY_WRAPPER.format(items=items)
    variables = {"owner": OWNER, "repo": REPO_NAME}

    try:
        body = post_graphql(query, variables)
    except GhCommandFailed as err:
        raise IssueBatchFailed(err.stderr) from err
    if "errors" in body:
        raise IssueBatchFailed("; ".join(e.get("message", "") for e in body["errors"]))
    repo = body["data"]["repository"]

    results: list[dict] = []
    for num in issue_numbers:
        node = repo.get(f"issue_{num}")
        if node is None:
            results.append({
                "number": num,
                "error": "not_found",
            })
            continue
        results.append(issue_node_to_dict(node))
    return results


def fetch_issues_batch(issue_numbers: list[int]) -> list[dict]:
    """
    Fetch details for a list of issue numbers in a single GraphQL query.

    Uses numbered aliases (issue_1234, …) so each issue is looked up by
    number in one round-trip. Returns entries in the same order as the input.

    Unlike PRs (which resolve to null), a dead issue number fails the whole
    batch, so when the failure names an unresolvable issue each number is
    retried on its own and reported as ``not_found`` instead of aborting.
    Any other failure (auth, outage, exhausted 504 retries) still aborts.
    """
    try:
        return _fetch_issues_batch(issue_numbers)
    except IssueBatchFailed as err:
        if "Could not resolve" not in str(err):
            print(f"GraphQL error: {err}", file=sys.stderr)
            sys.exit(1)
        print("  batch lookup failed, retrying issues one by one...",
              file=sys.stderr)
        results: list[dict] = []
        for num in issue_numbers:
            try:
                results.extend(_fetch_issues_batch([num]))
            except IssueBatchFailed:
                results.append({"number": num, "error": "not_found"})
        return results


def read_numbers_from_args(args: argparse.Namespace) -> list[int]:
    """Collect numbers from positional args, --file, and/or --stdin (deduplicated)."""
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


def cmd_issue(args: argparse.Namespace) -> None:
    """Handle the ``issue`` subcommand (batched by-number lookup, no filters)."""
    issue_numbers = read_numbers_from_args(args)
    if not issue_numbers:
        print("ERROR: no issue numbers provided (pass numbers, --file, or --stdin)",
              file=sys.stderr)
        sys.exit(1)

    print(f"Fetching {len(issue_numbers)} issues in batches of {PRS_BATCH_SIZE}...",
          file=sys.stderr)

    all_results: list[dict] = []
    for i in range(0, len(issue_numbers), PRS_BATCH_SIZE):
        batch = issue_numbers[i : i + PRS_BATCH_SIZE]
        print(f"  batch {i // PRS_BATCH_SIZE + 1}: issues {batch[0]}..{batch[-1]}",
              file=sys.stderr)
        all_results.extend(fetch_issues_batch(batch))

    print(json.dumps(all_results, indent=2))


# ─────────────────────────────────────────────
#  Subcommand: advisories
# ─────────────────────────────────────────────


def fetch_advisories() -> list[dict]:
    """Fetch all security advisories for the repository via REST API."""
    all_advisories: list[dict] = []
    page = 1

    while True:
        advisories = run_gh_rest(
            f"repos/{REPO}/security-advisories?per_page=100&page={page}"
        )
        all_advisories.extend(advisories)

        if len(advisories) < 100:
            break
        page += 1

    return all_advisories


def fetch_advisory(ghsa_id: str) -> dict:
    """Fetch a single security advisory by GHSA ID."""
    return run_gh_rest(f"repos/{REPO}/security-advisories/{ghsa_id}")


def format_advisory_summary(adv: dict) -> dict:
    """Extract a summary view of an advisory."""
    return {
        "ghsa_id": adv["ghsa_id"],
        "cve_id": adv.get("cve_id"),
        "severity": adv.get("severity"),
        "cvss_score": (adv.get("cvss") or {}).get("score"),
        "state": adv.get("state"),
        "summary": adv.get("summary"),
        "cwes": [c["cwe_id"] for c in adv.get("cwes", [])],
        "published_at": adv.get("published_at"),
        "closed_at": adv.get("closed_at"),
        "url": adv.get("html_url"),
    }


def format_advisory_detail(adv: dict) -> dict:
    """Extract full detail view of an advisory."""
    summary = format_advisory_summary(adv)
    summary["description"] = adv.get("description")
    summary["vulnerabilities"] = [
        {
            "package": v.get("package", {}).get("name"),
            "vulnerable_version_range": v.get("vulnerable_version_range"),
            "patched_versions": v.get("patched_versions"),
        }
        for v in adv.get("vulnerabilities", [])
    ]
    summary["credits"] = [
        {"login": c.get("user", {}).get("login"), "type": c.get("type")}
        for c in adv.get("credits_detailed", [])
    ]
    summary["created_at"] = adv.get("created_at")
    summary["updated_at"] = adv.get("updated_at")
    summary["withdrawn_at"] = adv.get("withdrawn_at")
    return summary


def cmd_advisories(args: argparse.Namespace) -> None:
    """Handle the ``advisories`` subcommand."""

    # ── Single advisory detail ──────────────────────────────
    if args.ghsa_id:
        ghsa_id = args.ghsa_id.upper()
        if not ghsa_id.startswith("GHSA-"):
            ghsa_id = f"GHSA-{ghsa_id}"
        print(f"Fetching advisory {ghsa_id}...", file=sys.stderr)
        adv = fetch_advisory(ghsa_id)
        print(json.dumps(format_advisory_detail(adv), indent=2))
        return

    # ── List all advisories ─────────────────────────────────
    print("Fetching security advisories...", file=sys.stderr)
    advisories = fetch_advisories()
    print(f"Fetched {len(advisories)} advisories", file=sys.stderr)

    results = [format_advisory_summary(adv) for adv in advisories]

    # Apply filters
    if args.severity:
        sev = args.severity.lower()
        results = [r for r in results if (r.get("severity") or "").lower() == sev]
        print(f"After severity filter ({sev}): {len(results)} advisories", file=sys.stderr)

    if args.state:
        st = args.state.lower()
        results = [r for r in results if (r.get("state") or "").lower() == st]
        print(f"After state filter ({st}): {len(results)} advisories", file=sys.stderr)

    print(json.dumps(results, indent=2))


# ─────────────────────────────────────────────
#  CLI entrypoint
# ─────────────────────────────────────────────


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Multi-purpose CLI helper for penpot/penpot GitHub operations"
    )
    sub = parser.add_subparsers(dest="command", required=True, title="subcommands")

    # --- issues ---
    p_issues = sub.add_parser("issues", help="List issues in a milestone (or use 'none' for unassigned)")
    p_issues.add_argument("milestone", help="Milestone title (e.g. '2.16.0') or 'none' for issues with no milestone")
    p_issues.add_argument(
        "--state", choices=["open", "closed", "all"], default="closed",
        help="Issue state filter (default: closed)"
    )
    p_issues.add_argument(
        "--exclude", "--exclude-labels",
        help="Comma-separated labels to exclude, e.g. 'release blocker,no changelog'"
    )
    p_issues.add_argument(
        "--label", "--labels",
        help="Comma-separated labels to include (issue must have ALL specified), e.g. 'bug' or 'bug,regression'"
    )
    p_issues.add_argument(
        "--compare",
        help="Path to CHANGES.md; only show issues NOT yet referenced in that file"
    )
    p_issues.add_argument(
        "--include-rejected", action="store_true",
        help="Include issues with 'Rejected' project status (excluded by default)"
    )
    p_issues.add_argument(
        "--include-tasks", action="store_true",
        help="Include issues with type 'Task' (excluded by default, they are internal chores)"
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

    # --- link-issue ---
    p_link = sub.add_parser(
        "link-issue",
        aliases=["link"],
        help="Explicitly link an issue to a pull request",
    )
    p_link.add_argument("issue_number", type=int, help="Issue number")
    p_link.add_argument("pr_number", type=int, help="Pull request number")
    p_link.set_defaults(func=cmd_link_issue)

    # --- issue (by-number lookup) ---
    p_issue = sub.add_parser(
        "issue", help="Fetch details for one or more issues by number (batched)"
    )
    p_issue.add_argument(
        "numbers", type=int, nargs="*",
        help="Issue numbers to fetch (space-separated)"
    )
    p_issue.add_argument(
        "--file", type=str,
        help="File with one issue number per line"
    )
    p_issue.add_argument(
        "--stdin", action="store_true",
        help="Read issue numbers from stdin (one per line)"
    )
    p_issue.set_defaults(func=cmd_issue)

    # --- advisories ---
    p_adv = sub.add_parser("advisories", help="List or inspect GitHub security advisories")
    p_adv.add_argument(
        "ghsa_id", nargs="?",
        help="GHSA ID to fetch (e.g. 'GHSA-xvj6-fh9w-gjw7'); omit to list all"
    )
    p_adv.add_argument(
        "--severity", choices=["critical", "high", "medium", "low"],
        help="Filter by severity level"
    )
    p_adv.add_argument(
        "--state", choices=["triage", "draft", "published", "closed", "withdrawn"],
        help="Filter by advisory state"
    )
    p_adv.set_defaults(func=cmd_advisories)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
