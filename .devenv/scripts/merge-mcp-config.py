#!/usr/bin/env python3
"""Combine a shared MCP-server config with a port-substituted template for one
AI coding-agent client.

Invoked per workspace by manage.sh's `write-instance-mcp-configs` (JSON
clients) and by `start-coding-agent` (Codex). Each supported client ships a
`.devenv/shared/<tool>.{json,toml}` (workspace-independent entries, e.g.
Playwright) and a `.devenv/templates/<tool>.{json,toml}` (per-workspace entries
with `${PENPOT_MCP_PORT}` / `${SERENA_MCP_PORT}` placeholders). This script
combines the two for the target client.

Two output modes are supported:

  json         Deep-merge two JSON documents under a configurable top-level key
               (`mcpServers` for Claude Code, `mcp` for opencode, `servers` for
               VS Code Copilot) and write the result to <out>. Same-name
               entries in the template override entries in shared. With
               --merge-into-existing, any pre-existing <out> file is loaded as
               the lowest-precedence layer first, so entries the developer
               already had are preserved (ours win on name collision). This is
               used for VS Code's auto-discovered `.vscode/mcp.json`, which on
               ws0 IS the live repo's file and may hold the developer's own
               servers; the Claude/opencode outputs live in a dedicated,
               gitignored `.devenv/mcp/` path and are written without the flag
               (a clean overwrite).

  codex-args   Deep-merge the two TOML chunks and print one
               `dotted.key=<toml-value>` assignment per line to stdout (no
               <out> file). The caller wraps each line in a `codex -c` flag.
               Codex has no way to load an MCP config from an arbitrary file
               path (CODEX_HOME would relocate auth/history too), so rather than
               writing the auto-discovered `.codex/config.toml` we inject our
               servers as ephemeral per-invocation overrides. This never
               touches the developer's project- or user-level Codex config.

In both modes, `${VAR}` placeholders inside *either* chunk are resolved from
the current environment (only template chunks carry placeholders in practice,
but the substitution is uniform either way) using Python's
`os.path.expandvars`. Undefined placeholders are left as `${VAR}` literal text
-- callers (i.e. manage.sh) are responsible for exporting the variables before
invoking the script.

Usage:
  merge-mcp-config.py --format json --key <key> [--merge-into-existing] \
      <shared> <template> <out>
  merge-mcp-config.py --format codex-args <shared> <template>

Exit codes:
  0  success
  2  argparse error (missing required option, bad value, unreadable input)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tomllib
from pathlib import Path


def merge_json(
    shared_path: Path,
    tpl_path: Path,
    out_path: Path,
    key: str,
    merge_into_existing: bool,
) -> None:
    """Deep-merge JSON documents under a single top-level dict key into out.

    Precedence (lowest to highest): an existing <out> file (only when
    merge_into_existing is set), then shared, then the template. Entries under
    `key` are merged by name, so the template wins on a name collision while
    every other entry the lower layers contributed is kept. Top-level keys
    other than `key` come from the existing file and shared (shared wins).
    """
    shared = json.loads(shared_path.read_text())
    tpl = json.loads(os.path.expandvars(tpl_path.read_text()))

    base: dict = {}
    if merge_into_existing and out_path.exists():
        base = json.loads(out_path.read_text())

    merged: dict = {**base, **shared}
    merged[key] = {**base.get(key, {}), **shared.get(key, {}), **tpl.get(key, {})}

    out_path.write_text(json.dumps(merged, indent=2) + "\n")


def _deep_merge(base: dict, overlay: dict) -> dict:
    """Recursively merge overlay into base; overlay wins on scalar/list keys."""
    out = dict(base)
    for k, v in overlay.items():
        if isinstance(out.get(k), dict) and isinstance(v, dict):
            out[k] = _deep_merge(out[k], v)
        else:
            out[k] = v
    return out


def _toml_value(value: object) -> str:
    """Serialize a scalar/list as a TOML literal for a `codex -c` value.

    bool is checked before int because `isinstance(True, int)` is True. Strings
    are emitted as JSON strings, which are valid TOML basic strings for the
    ASCII values our configs carry (commands, args, URLs). Tables never reach
    here -- they are flattened into dotted keys by _flatten.
    """
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return repr(value)
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, list):
        return "[" + ", ".join(_toml_value(v) for v in value) + "]"
    raise TypeError(f"unsupported TOML value type: {type(value).__name__}")


_BARE_KEY = re.compile(r"^[A-Za-z0-9_-]+$")


def _key_segment(seg: str) -> str:
    """A dotted-key segment: bare if TOML-safe, else a quoted key."""
    return seg if _BARE_KEY.match(seg) else json.dumps(seg)


def _flatten(obj: dict, prefix: list[str]):
    """Yield (dotted-path-segments, leaf-value) for every non-table leaf.

    Lists are leaves (TOML arrays), so we do not recurse into them; nested
    tables (e.g. an `env` table) are flattened into further dotted keys.
    """
    for k, v in obj.items():
        path = prefix + [k]
        if isinstance(v, dict):
            yield from _flatten(v, path)
        else:
            yield path, v


def emit_codex_args(shared_path: Path, tpl_path: Path) -> None:
    """Print `dotted.key=<toml-value>` lines from the merged TOML chunks."""
    shared = tomllib.loads(os.path.expandvars(shared_path.read_text()))
    tpl = tomllib.loads(os.path.expandvars(tpl_path.read_text()))
    merged = _deep_merge(shared, tpl)
    for path, value in _flatten(merged, []):
        dotted = ".".join(_key_segment(s) for s in path)
        sys.stdout.write(f"{dotted}={_toml_value(value)}\n")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__.split("\n\n", 1)[0],
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--format",
        choices=("json", "codex-args"),
        required=True,
        help="Output mode: 'json' writes a merged file; 'codex-args' prints -c assignments.",
    )
    parser.add_argument(
        "--key",
        help="Top-level JSON key under which MCP entries live (required for --format json).",
    )
    parser.add_argument(
        "--merge-into-existing",
        action="store_true",
        help="json only: layer the merge on top of an existing <out> file, "
        "preserving entries already there (ours still win on name collision).",
    )
    parser.add_argument("shared", type=Path, help="Path to the shared chunk.")
    parser.add_argument("template", type=Path, help="Path to the port-placeholder template chunk.")
    parser.add_argument(
        "out",
        type=Path,
        nargs="?",
        help="Path the merged result is written to (json only; codex-args writes stdout).",
    )
    args = parser.parse_args(argv)

    if args.format == "json":
        if not args.key:
            parser.error("--key is required when --format json")
        if args.out is None:
            parser.error("out is required when --format json")
        merge_json(args.shared, args.template, args.out, args.key, args.merge_into_existing)
    else:  # codex-args
        if args.key:
            parser.error("--key is not accepted when --format codex-args")
        if args.merge_into_existing:
            parser.error("--merge-into-existing is not accepted when --format codex-args")
        if args.out is not None:
            parser.error("out path is not accepted when --format codex-args (result goes to stdout)")
        emit_codex_args(args.shared, args.template)

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
