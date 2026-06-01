#!/usr/bin/env python3
"""
Taiga API client — fetch public issues, user stories, and tasks from the
Penpot project (id 345963) without authentication.

Usage:
  python3 tools/taiga.py <taiga-url>
  python3 tools/taiga.py <type> <ref>
  python3 tools/taiga.py [--json] <taiga-url>
  python3 tools/taiga.py [--json] <type> <ref>

Examples:
  python3 tools/taiga.py https://tree.taiga.io/project/penpot/issue/13714
  python3 tools/taiga.py --json https://tree.taiga.io/project/penpot/us/14128
  python3 tools/taiga.py task 13648
"""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request

API_BASE = "https://api.taiga.io/api/v1"
PROJECT_ID = 345963

ENDPOINT_MAP = {
    "issue": "issues",
    "us": "userstories",
    "task": "tasks",
}

TYPE_LABELS = {
    "issue": "Issue",
    "us": "User Story",
    "task": "Task",
}


# ── URL Parsing ──────────────────────────────────────────────────────────────

def parse_taiga_url(url: str) -> tuple[str, int] | None:
    """Extract (type, ref) from a tree.taiga.io URL.

    Supported patterns:
      .../project/penpot/issue/13714
      .../project/penpot/us/14128
      .../project/penpot/task/13648
    """
    m = re.search(r"/project/penpot/(issue|us|task)/(\d+)", url)
    if not m:
        return None
    return m.group(1), int(m.group(2))


# ── API call ─────────────────────────────────────────────────────────────────

def fetch_item(endpoint: str, ref: int) -> dict | None:
    """Fetch a single item by ref using the 'by_ref' endpoint."""
    url = f"{API_BASE}/{endpoint}/by_ref?ref={ref}&project={PROJECT_ID}"
    try:
        with urllib.request.urlopen(url, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        print(f"Error: HTTP {e.code} — {e.reason}", file=sys.stderr)
        if e.code == 404:
            print(
                f"  Item (ref={ref}) not found in project {PROJECT_ID}.",
                file=sys.stderr,
            )
        return None
    except urllib.error.URLError as e:
        print(f"Error: {e.reason}", file=sys.stderr)
        return None
    except json.JSONDecodeError as e:
        print(f"Error: invalid JSON response — {e}", file=sys.stderr)
        return None


# ── Output formatting ────────────────────────────────────────────────────────

def _val(value, default="—"):
    return value if value is not None else default


def _tag_list(tags):
    """Pretty-print tag list. Tags are arrays of [name, color] pairs."""
    if not tags:
        return "—"
    names = [t[0] if isinstance(t, list) else str(t) for t in tags]
    return ", ".join(names)


def _extra_name(extra_info):
    """Extract a display name from an *_extra_info dict."""
    if not extra_info:
        return "—"
    return extra_info.get("full_name_display") or extra_info.get("username") or "—"


def _status_name(status_info):
    """Extract status name from status_extra_info."""
    if not status_info:
        return "—"
    return status_info.get("name", "—")


def _project_name(proj_info):
    """Extract project name from project_extra_info."""
    if not proj_info:
        return "—"
    return proj_info.get("name", "—")


def _assignee(item):
    """Return the assignee display name."""
    return _extra_name(item.get("assigned_to_extra_info"))


def _owner(item):
    return _extra_name(item.get("owner_extra_info"))


def format_summary(item: dict, item_type: str) -> str:
    """Build a printable summary matching the requested format."""
    label = TYPE_LABELS.get(item_type, item_type.capitalize())
    subject = item.get("subject", "(no subject)")
    ref = item.get("ref", "?")
    status = _status_name(item.get("status_extra_info"))
    assignee = _assignee(item)
    owner = _owner(item)
    created = item.get("created_date", "")[:10] if item.get("created_date") else ""
    tags = _tag_list(item.get("tags", []))

    # Title line
    title = f"{label} #{ref} — {subject}"

    # Fields section (no indent)
    fields = []
    fields.append(f"Status:     {status}")

    if item_type == "us":
        milestone = item.get("milestone_slug") or ""
        points = item.get("points") or {}
        point_count = len(points)
        fields.append(f"Milestone:  {milestone}")
        fields.append(f"Points:     {point_count} role(s)")
    elif item_type == "task":
        milestone = item.get("milestone_slug") or ""
        parent = item.get("user_story")
        fields.append(f"Milestone:  {milestone}")
        fields.append(f"Parent US:  {parent if parent else '—'}")
    elif item_type == "issue":
        issue_type_id = item.get("type", "")
        severity_id = item.get("severity", "")
        priority_id = item.get("priority", "")
        fields.append(f"Type ID:    {issue_type_id}")
        fields.append(f"Severity ID: {severity_id}")
        fields.append(f"Priority ID: {priority_id}")

    fields.append(f"Assignee:   {assignee}")
    fields.append(f"Author:     {owner}")
    fields.append(f"Created:    {created}")
    fields.append(f"Tags:       {tags}")

    url = f"https://tree.taiga.io/project/penpot/{item_type}/{ref}"
    fields.append(f"URL:        {url}")

    # Assemble output
    sep = "================================"
    parts = [title, sep]
    parts.extend(fields)

    # Full description after second separator
    desc = item.get("description") or ""
    if desc.strip():
        parts.append(sep)
        parts.append(desc)

    return "\n".join(parts)


# ── CLI ───────────────────────────────────────────────────────────────────────

def build_parser():
    parser = argparse.ArgumentParser(
        description="Fetch public items from the Penpot Taiga project.",
        epilog=(
            "Examples:\n"
            "  %(prog)s https://tree.taiga.io/project/penpot/issue/13714\n"
            "  %(prog)s --json us 14128\n"
            "  %(prog)s task 13648"
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--json",
        action="store_true",
        dest="raw_json",
        help="Output raw JSON instead of formatted summary.",
    )
    parser.add_argument(
        "args",
        nargs="+",
        help='Either a Taiga URL, or "<type> <ref>" (e.g. issue 13714).',
    )
    return parser


def main():
    parser = build_parser()
    opts = parser.parse_args()

    # Determine (type, ref) from arguments
    item_type: str | None = None
    ref: int | None = None

    if len(opts.args) == 1:
        # Single argument — must be a Taiga URL
        parsed = parse_taiga_url(opts.args[0])
        if parsed is None:
            print(
                "Error: could not parse Taiga URL. "
                'Expected format: https://tree.taiga.io/project/penpot/<type>/<ref>',
                file=sys.stderr,
            )
            sys.exit(1)
        item_type, ref = parsed
    elif len(opts.args) == 2:
        item_type, ref_str = opts.args
        if item_type not in ENDPOINT_MAP:
            print(
                f"Error: unknown type '{item_type}'. "
                f"Expected one of: {', '.join(ENDPOINT_MAP)}",
                file=sys.stderr,
            )
            sys.exit(1)
        try:
            ref = int(ref_str)
        except ValueError:
            print(f"Error: ref must be a number, got '{ref_str}'", file=sys.stderr)
            sys.exit(1)
    else:
        parser.print_help()
        sys.exit(1)

    endpoint = ENDPOINT_MAP[item_type]
    item = fetch_item(endpoint, ref)

    if item is None:
        sys.exit(1)

    if opts.raw_json:
        print(json.dumps(item, indent=2, ensure_ascii=False))
    else:
        print(format_summary(item, item_type))


if __name__ == "__main__":
    main()
