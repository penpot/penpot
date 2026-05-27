#!/usr/bin/env python3
"""Merge a shared MCP-server config with a port-substituted template into one
output file for one AI coding-agent client.

Invoked per workspace by manage.sh's `write-instance-mcp-configs`. Each
supported client (Claude Code, opencode, VS Code Copilot, OpenAI Codex CLI)
ships a `.devenv/shared/<tool>.{json,toml}` (workspace-independent entries,
e.g. Playwright) and a `.devenv/templates/<tool>.{json,toml}` (per-workspace
entries with `${PENPOT_MCP_PORT}` / `${SERENA_MCP_PORT}` placeholders). This
script combines the two into the final config that the client loads.

Two formats are supported:

  json   Deep-merge two JSON documents under a configurable top-level key
         (`mcpServers` for Claude Code, `mcp` for opencode, `servers` for VS
         Code Copilot). Same-name entries in the template override entries
         in shared.
  toml   Concatenate two TOML chunks. Codex's `[mcp_servers.<name>]` layout
         puts every server in its own top-level table, so a textual concat
         is equivalent to a structural merge -- and avoids pulling in a
         third-party TOML writer.

In both modes, `${VAR}` placeholders inside *either* chunk are resolved
from the current environment (only template chunks carry placeholders in
practice, but the substitution is uniform either way) using Python's
`os.path.expandvars`. Undefined placeholders are left as `${VAR}` literal
text -- callers (i.e. manage.sh) are responsible for exporting the
variables before invoking the script.

Usage:
  merge-mcp-config.py --format json --key <key> <shared> <template> <out>
  merge-mcp-config.py --format toml             <shared> <template> <out>

Exit codes:
  0  success
  2  argparse error (missing required option, bad value, unreadable input)
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path


def merge_json(shared_path: Path, tpl_path: Path, out_path: Path, key: str) -> None:
    """Deep-merge two JSON documents under a single top-level dict key.

    Both files must be valid JSON. Entries from the template under `key` take
    precedence over entries from the shared chunk with the same name. Keys
    other than `key` are taken from shared (the template is only expected to
    contribute MCP-server entries; everything else lives in shared).
    """
    shared = json.loads(shared_path.read_text())
    tpl = json.loads(os.path.expandvars(tpl_path.read_text()))

    merged: dict = {**shared}
    merged[key] = {**shared.get(key, {}), **tpl.get(key, {})}

    out_path.write_text(json.dumps(merged, indent=2) + "\n")


def concat_toml(shared_path: Path, tpl_path: Path, out_path: Path) -> None:
    """Concatenate two TOML chunks separated by a blank line.

    Relies on the convention that each MCP server occupies its own
    `[mcp_servers.<name>]` table at the top level; no two chunks may
    declare the same server name (the resulting file would parse but
    contain duplicate tables, which Codex would reject).
    """
    chunks = [
        os.path.expandvars(p.read_text()).rstrip("\n")
        for p in (shared_path, tpl_path)
    ]
    out_path.write_text("\n\n".join(chunks) + "\n")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.split("\n\n", 1)[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--format",
        choices=("json", "toml"),
        required=True,
        help="Format of the inputs and the output.",
    )
    parser.add_argument(
        "--key",
        help="Top-level JSON key under which MCP entries live (required for --format json).",
    )
    parser.add_argument("shared", type=Path, help="Path to the shared chunk.")
    parser.add_argument("template", type=Path, help="Path to the port-placeholder template chunk.")
    parser.add_argument("out", type=Path, help="Path the merged result is written to.")
    args = parser.parse_args(argv)

    if args.format == "json":
        if not args.key:
            parser.error("--key is required when --format json")
        merge_json(args.shared, args.template, args.out, args.key)
    else:
        if args.key:
            parser.error("--key is not accepted when --format toml")
        concat_toml(args.shared, args.template, args.out)

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
