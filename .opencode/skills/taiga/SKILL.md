---
name: taiga
description: Fetch information from Taiga public API for the Penpot project (id 345963) — issues, user stories, and tasks, without authentication.
metadata: {"clawdbot":{"requires":{"bins":["python3"]}}}
---

# Taiga API Skill

Fetch information from Taiga public API for the **Penpot** project
(project id: `345963`, slug: `penpot`).

**No authentication required** — only public project data is accessed.

## Prerequisites

- `python3` — the `tools/taiga.py` CLI script is self-contained (stdlib only)

## Quick Start

The easiest way is to use the bundled Python script:

```bash
# Pass a Taiga URL directly
python3 tools/taiga.py https://tree.taiga.io/project/penpot/issue/13714

# Or use "<type> <ref>" syntax
python3 tools/taiga.py us 14128
python3 tools/taiga.py task 13648

# Add --json for raw output
python3 tools/taiga.py --json issue 13714

# See full usage
python3 tools/taiga.py --help
```

## URL Pattern Reference

Taiga web URLs follow these patterns:

| Type | Web URL Pattern |
|------|----------------|
| Issue | `https://tree.taiga.io/project/penpot/issue/<REF>` |
| User Story | `https://tree.taiga.io/project/penpot/us/<REF>` |
| Task | `https://tree.taiga.io/project/penpot/task/<REF>` |

To extract the **type** and **ref** from a URL:
- `issue/13714` → type=`issue`, ref=`13714`
- `us/14128` → type=`us`, ref=`14128`
- `task/13648` → type=`task`, ref=`13648`

## Python Script Reference

The `tools/taiga.py` script wraps the Taiga API into a single convenient CLI
with sensible defaults.

### Usage

```
python3 tools/taiga.py <taiga-url>
python3 tools/taiga.py <type> <ref>
python3 tools/taiga.py [--json] <taiga-url>
python3 tools/taiga.py [--json] <type> <ref>
```

### Examples

```bash
# By URL (recommended — no need to think about type/ref)
python3 tools/taiga.py https://tree.taiga.io/project/penpot/issue/13714

# By type and ref
python3 tools/taiga.py us 14128
python3 tools/taiga.py task 13648

# Raw JSON output
python3 tools/taiga.py --json issue 13714
```

### Output

The script prints a clean, structured summary:

```text
User Story #11964 — 🔴 [DESIGN TOKENS] Typography Composite Input
================================
Status:     Defining
Milestone:  design-systems-sprint-26
Points:     3 role(s)
Assignee:   Natacha Menjibar
Author:     Natacha Menjibar
Created:    2025-09-01
Tags:       iop-design-tokens
URL:        https://tree.taiga.io/project/penpot/us/11964
================================
<full description text, unmodified>
```

The fields section includes type-specific information:
- **Issues:** Status, Type ID, Severity ID, Priority ID
- **User Stories:** Status, Milestone, Points
- **Tasks:** Status, Milestone, Parent US

## Reference

- API docs: https://docs.taiga.io/api.html
- Taiga instance: https://tree.taiga.io
- API base: https://api.taiga.io/api/v1
- Penpot project id: `345963`
- Penpot project slug: `penpot`
