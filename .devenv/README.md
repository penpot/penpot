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

One more file is generated outside `.devenv/`, in the directory VS Code itself
auto-discovers (gitignored):

```
.vscode/mcp.json           # auto-loaded by GitHub Copilot in VS Code
```

Codex is the exception: it has no way to load an MCP config from an arbitrary
path, and its only project-level config file (`.codex/config.toml`) is one a
developer may already own. So we do **not** write a file for Codex at all —
`start-coding-agent codex` injects our servers as `-c` command-line overrides
built fresh from `shared/codex.toml` + `templates/codex.toml` at launch.

* **`shared/`** holds MCP entries that don't depend on the workspace — the
  browser-driving Playwright server today, plus any other workspace-independent
  servers we add later. Same content in every workspace, so it's a static
  checked-in file.
* **`templates/`** holds the workspace-specific entries (Penpot MCP, Serena
  MCP) with `${PENPOT_MCP_PORT}` and `${SERENA_MCP_PORT}` placeholders. The
  placeholders are resolved per-workspace from the port-base constants in
  `manage.sh`.
* **`mcp/`** (Claude Code, opencode) is the result of merging `shared/` with
  the port-substituted `templates/`. `manage.sh` writes these on every
  `run-devenv --agentic` pass. Gitignored, dedicated paths with no developer
  content — never edit by hand, your edits will be overwritten on the next
  reconcile.
* **`.vscode/mcp.json`** is the same merge, but written to the path VS Code
  auto-discovers. Because on `ws0` that path *is* the live repo's own file, the
  reconcile **deep-merges** into it: any servers you added yourself are kept,
  and only the entries we manage (`penpot`, `serena-devenv`, `playwright`) are
  (re)written to the current ports. On `ws1+` the file doesn't exist yet, so it
  is created from scratch.
* **`scripts/merge-mcp-config.py`** is the generator. Its `json` mode does the
  JSON deep-merge (with `--merge-into-existing` for the VS Code path); its
  `codex-args` mode prints the `-c` assignments for Codex. `manage.sh`'s
  `_merge-mcp-config-json` helper is a thin shim over the former, and
  `start-coding-agent` calls the latter directly. Run
  `python3 .devenv/scripts/merge-mcp-config.py --help` for the CLI.

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
# Codex: pass our servers as -c overrides (no config file is written).
codex $(python3 .devenv/scripts/merge-mcp-config.py --format codex-args \
          .devenv/shared/codex.toml .devenv/templates/codex.toml \
          | sed 's/^/-c /')
```

`start-coding-agent codex` does the `-c` wiring for you (and resolves the
workspace's ports first). Because our servers arrive as command-line
overrides, no "trusted project" prompt is involved for them — that prompt only
gates Codex's own `.codex/config.toml`, which we never write.

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
* **VS Code Copilot** — the reconcile deep-merges into `.vscode/mcp.json`, so
  any servers you add there yourself are preserved (only `penpot`,
  `serena-devenv` and `playwright` are rewritten). To shadow one of *ours*,
  put an entry under the same name in your VS Code user-profile MCP config —
  it is loaded alongside the workspace file and wins.
* **Codex CLI** — our servers arrive as `-c` overrides, which are Codex's
  highest-precedence layer, so they win over a same-named `[mcp_servers.<name>]`
  in your `~/.codex/config.toml` or a project `.codex/config.toml`. To override
  one of ours, append your own `-c` after the client name — extra args are
  forwarded after ours and the later `-c` wins, e.g.
  `./manage.sh start-coding-agent codex -- -c 'mcp_servers.penpot.url="…"'`.

See `docs/technical-guide/developer/agentic-devenv.md` for the broader
client-configuration story (browser remote debugging, AI-client config
schemas, manual setup for unsupported clients).
