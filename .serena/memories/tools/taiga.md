# Taiga API client

`tools/taiga.py` fetches public issues, user stories, and tasks from the
Penpot Taiga project (id 345963) without authentication.

## When to use

- Fetching details of a Taiga issue, user story, or task by URL or ref number.
- Inspecting status, assignee, tags, description, and other metadata.
- Piping structured JSON into other scripts (with `--json`).

## How to use

```bash
# Fetch by full Taiga URL
python3 tools/taiga.py https://tree.taiga.io/project/penpot/issue/13714

# Fetch by type and ref number
python3 tools/taiga.py issue 13714
python3 tools/taiga.py us 14128
python3 tools/taiga.py task 13648

# Output raw JSON instead of formatted summary
python3 tools/taiga.py --json issue 13714
python3 tools/taiga.py --json https://tree.taiga.io/project/penpot/us/14128
```

## Supported types

| Type | Description |
|------|-------------|
| `issue` | Bug reports, feature requests |
| `us` | User stories |
| `task` | Implementation tasks |

## Output

Default output is a formatted summary with title, status, assignee, author,
tags, URL, and description. Use `--json` for the raw API response.

## Prerequisites

- Python 3.8+ (standard library only, no dependencies).
- Network access to `api.taiga.io`.
