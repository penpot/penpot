# `.devenv/` — Per-Workspace AI-Client MCP Configs

This directory carries the pieces needed to point an AI coding agent
(currently Claude Code, opencode, VS Code Copilot, and the OpenAI Codex CLI)
at the MCP servers running inside the parallel devenv instance the developer
is currently working in. Every parallel workspace (`ws0`, `ws1`, …) has its
own copy because the Penpot MCP and Serena MCP host ports are
workspace-specific.

## Layout

```
.devenv/
  README.md
  scripts/
    merge-mcp-config.py    # generator helper invoked by manage.sh
  shared/                  # committed; workspace-independent entries
    claude-code.json       # Playwright — same for every workspace
    opencode.json
    vscode.json
    codex.toml
  templates/               # committed; entries with ${...} port placeholders
    claude-code.json       # Penpot MCP, Serena MCP — port is the only diff
    opencode.json
    vscode.json
    codex.toml
  mcp/                     # gitignored; written by manage.sh per workspace
    claude-code.json       # loaded via Claude Code's --mcp-config flag
    opencode.json          # loaded via OPENCODE_CONFIG env var
```

Two more generated files live outside `.devenv/`, in the directories the
clients themselves auto-discover (both gitignored):

```
.vscode/mcp.json           # auto-loaded by GitHub Copilot in VS Code
.codex/config.toml         # auto-loaded by Codex CLI; "trusted project" required
```

* **`shared/`** holds MCP entries that don't depend on the workspace — the
  browser-driving Playwright server today, plus any other workspace-independent
  servers we add later. Same content in every workspace, so it's a static
  checked-in file.
* **`templates/`** holds the workspace-specific entries (Penpot MCP, Serena
  MCP) with `${PENPOT_MCP_PORT}` and `${SERENA_MCP_PORT}` placeholders. The
  placeholders are resolved per-workspace from the port-base constants in
  `manage.sh`.
* **`mcp/`** plus the two tool-expected paths (`.vscode/mcp.json`,
  `.codex/config.toml`) are the result of merging `shared/` with the
  port-substituted `templates/`. `manage.sh` writes them on every
  `run-devenv-agentic` pass. Gitignored — never edit by hand, your edits will
  be overwritten on the next reconcile.
* **`scripts/merge-mcp-config.py`** is the generator that does the merge.
  `manage.sh`'s `_merge-mcp-config-{json,toml}` helpers are thin shims over
  it. Run `python3 .devenv/scripts/merge-mcp-config.py --help` for the CLI;
  edit the script if you need to change merge semantics, add a new format,
  or support a new template shape.

## Launching a coding agent

The easiest path is the wrapper command, which knows the right flags per
client, `cd`'s into the target workspace, and refuses to launch unless the
target instance is running and its MCP config has been generated:

```bash
# Default target is ws0 (the live repo).
./manage.sh start-coding-agent claude               [...args to forward]
./manage.sh start-coding-agent opencode             [...args to forward]
./manage.sh start-coding-agent vscode               [...args to forward to 'code']
./manage.sh start-coding-agent codex                [...args to forward]

# Target a parallel workspace with --ws N. N is an integer (non-negative);
# 'main', 'ws1' and similar spellings are rejected.
./manage.sh start-coding-agent claude --ws 1
./manage.sh start-coding-agent opencode --ws 2
```

Equivalents by hand (run from inside the workspace directory):

```bash
claude --mcp-config .devenv/mcp/claude-code.json
OPENCODE_CONFIG=.devenv/mcp/opencode.json opencode
code "$PWD"                  # VS Code auto-discovers .vscode/mcp.json
codex                        # Codex auto-discovers .codex/config.toml
```

The first `codex` invocation in a workspace will prompt you to **trust the
project** — Codex only loads `.codex/config.toml` from trusted projects.

## Overriding our entries

Both the auto-discovered configs and the launcher-loaded configs sit *on top
of* the developer's global config (with varying precedence rules). All four
clients offer escape hatches for shadowing entries we ship:

* **Claude Code** — `claude mcp add --scope local …` installs a private entry
  that overrides the one in `mcp/claude-code.json`. Local scope wins.
* **opencode** — drop an `opencode.json` at the repo root with the override
  entries you need. opencode's precedence chain is *global → `OPENCODE_CONFIG`
  → project*, so the project file always wins. The root `opencode.json` is
  gitignored on purpose, since these overrides are personal.
* **VS Code Copilot** — VS Code's user-profile MCP config and `.vscode/mcp.json`
  are both loaded; same-name entries can be shadowed in the user profile.
* **Codex CLI** — entries in `~/.codex/config.toml` override the project file
  for the same `[mcp_servers.<name>]` table.

See `docs/technical-guide/developer/agentic-devenv.md` for the broader
client-configuration story (browser remote debugging, AI-client config
schemas, manual setup for unsupported clients).
