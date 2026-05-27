---
title: 3.11. Agentic Development Environment
desc: Dive into agentic Penpot development.
---

# Agentic Development Environment

The agentic DevEnv is an extension of the standard DevEnv (the
[general DevEnv instructions](/technical-guide/developer/devenv/) apply),
optimised for AI agent-based development. It adds MCP servers (Penpot,
Serena, Playwright) and supports a launcher that wires them into your AI client.

Two things to know up front:

- **Parallel workspaces are first-class.** Run several devenv instances side
  by side - one per AI agent if you like - each with its own source-tree
  clone, ports, and tmux session. Pass `--ws N` to target one.
- **Your existing AI-client config is preserved.** The launcher loads a
  per-workspace MCP config on top of your global one.

## Quick Start

1. **Bring up one or more workspaces**[^cfg]:

   ```bash
   ./manage.sh run-devenv-agentic              # ws0 (the live repo)
   ./manage.sh run-devenv-agentic --ws 1       # ws1 (sibling clone)
   ```

   Add `--ws 2`, `--ws 3`, … for more parallel workspaces.

2. **Launch a browser with remote debugging enabled:**

   For example, with Chrome:

   ```bash
   google-chrome --remote-debugging-port=9222 --user-data-dir="$HOME/.chrome-debug-profile"
   ```

3. **Open Penpot in that browser:**

   - ws0: <https://localhost:3449>
   - ws1: <https://localhost:13449>
   - etc. (ports are offset by `10000 × N` for `wsN`)

   On first login per account, open settings → Integrations and toggle
   "MCP Server" on. The agentic DevEnv runs the MCP server in single-user
   mode - the key and proxied URL shown in the UI are not needed.

4. **Launch your AI client** against the workspace you want it to drive:

   ```bash
   ./manage.sh start-coding-agent claude              # ws0
   ./manage.sh start-coding-agent claude --ws 1       # ws1
   ```

   Supported clients: `claude` | `opencode` | `vscode` | `codex`.
5. **Attach to the tmux session** for the workspace (optional):

   ```bash
   ./manage.sh attach-devenv            # ws0
   ./manage.sh attach-devenv --ws 1     # ws1
   ```

6. **Shut down workspaces** with `./manage.sh stop-devenv`, either one by one or all at once.
   You cannot shut down `ws0` if any other workspace is still running, since it's the worker-bearer.
   Shared infrastructure will be cleaned up when the last workspace is stopped.

Optional: watch Serena's activity in its dashboard
(<http://localhost:14182> for ws0, <http://localhost:24182> for ws1, etc.).

[^cfg]: One-time, if you don't already have it: set
`penpotFlags = "enable-mcp"` in `frontend/resources/public/js/config.js`
(gitignored; create if missing).

## Capabilities

The agentic DevEnv leverages several MCP servers in order to provide AI agents
with a comprehensive toolbox for Penpot development:

* **Penpot MCP Server** provides tools for directly interacting with a live Penpot instance,
  enabling the agent to
  * execute JavaScript code in the frontend (using the plugin API),
  * execute ClojureScript code in the frontend (REPL),
  * import .penpot files for reproducing issues,
  * export design elements as images, and more.
* **Serena MCP Server** provides code intelligence tools with support for Clojure and TypeScript.
  Its memory system is used to organise project knowledge in a context-efficient manner.
* **Playwright MCP Server** provides tools for browser remote control.

Equipped with the tools provided by these MCP servers, the agent can fully close the development loop,
i.e. it can ...
* retrieve information on an issue from GitHub,
* import relevant design files for reproduction,
* execute JavaScript and ClojureScript code directly in Penpot in order to
   * simulate user interactions (e.g. to reproduce an issue),
   * test hypotheses on the root cause of an issue, and
   * experiment with implementations before touching the actual codebase,
* detect, analyse and recover from crashes in the frontend,
* make code changes (using IDE-like symbolic operations)
* test the changes in the live Penpot instance, and
* create commits and PRs resolving the issue.

## The flow in detail

### First-time setup

**The MCP frontend flag.** Edit `frontend/resources/public/js/config.js`
(create it if missing) and ensure `penpotFlags` contains `enable-mcp`:

```javascript
var penpotFlags = "enable-mcp";
```

The file is gitignored and lives in the live repo only. On every
`run-devenv-agentic` call it is read directly for ws0; for wsN (N ≥ 1) it is
copied into the workspace clone on the **initial** sync only - subsequent
`--sync` passes leave the workspace's copy alone so per-workspace
customisations survive. `run-devenv-agentic` refuses to start if the file is
missing.

**Browser remote debugging.** The Playwright MCP server drives a real
browser instance over the Chrome DevTools protocol. To enable it, launch a
Chromium-based browser (Chrome, Vivaldi, Opera, …) with the
`--remote-debugging-port` flag and a separate user-data directory:

```bash
google-chrome --remote-debugging-port=9222 --user-data-dir="$HOME/.chrome-debug-profile"
```

Verify it works by visiting `http://127.0.0.1:9222/json/version`. If you
change the port, update the Playwright MCP entry in `.devenv/shared/*.json`
accordingly. For security reasons, do not enable remote debugging on the
profile you use for regular browsing.

**Enable the MCP integration in Penpot.** The Penpot UI has a per-account
MCP toggle. After logging into your Penpot instance at
[https://localhost:3449](https://localhost:3449), open account settings,
click "Integrations" in the sidebar, and enable the "MCP Server" toggle.
The agentic DevEnv runs the MCP server in single-user mode, so the
generated key and proxied URL printed in the UI are *not* needed - only the
toggle itself matters.

**(Optional) custom devenv image.** Only needed if you want to modify the
devenv image itself (add a tool, change a base layer):

```bash
./manage.sh build-devenv --local
```

The default `run-devenv-agentic` flow pulls the published image
automatically, so regular users never run this.

### Bringing up workspaces

```bash
./manage.sh run-devenv-agentic \
    [--ws N] [--sync] [--serena-context CTX] \
    [--git-user-name NAME] [--git-user-email EMAIL]
```

Brings one agentic instance up. Errors out if the target is already running.

`--ws N` (N ≥ 1) auto-starts ws0 first if it is not already up - ws0 is the
worker-bearer and must be running whenever any wsN is. Per-instance ports
are offset by `10000 × N` (ws1's MCP at `http://localhost:14401/mcp`, Serena
MCP at `http://localhost:24181`, Serena dashboard at
`http://localhost:24182`, etc.). `manage.sh` prints the full URL set on
every bring-up so you don't compute offsets by hand. See the
[Dev environment guide](./devenv.md) for the workspace lifecycle, `--sync`
semantics, and stop ordering.

**Git identity for agent commits.** Coding agents typically need to commit
inside the devenv, so `run-devenv-agentic` wires a Git identity into the
container's global config on every bring-up. By default it propagates the
host's effective `git config user.{name,email}` (local repo override wins
over `~/.gitconfig`, matching what `git commit` on the host would record).
Override with `--git-user-name "Full Name"` / `--git-user-email
you@example.com` when you want agent commits to carry an identity different
from your normal one. Without either source the script warns and proceeds;
commits inside the devenv will fail until you fix it. See the
[Dev environment guide](./devenv.md#git-identity-inside-the-container) for
the full mechanics.

> **Note:** the MCP and Serena tmux windows are only added when the tmux
> session is first created. If a workspace was already brought up with
> `./manage.sh run-devenv` (non-agentic), stop it before re-running
> agentically:
>
> ```bash
> ./manage.sh stop-devenv
> ./manage.sh run-devenv-agentic
> ```

### Launching an AI client

The agentic environment supports any AI client, one just needs to set the right MCP config,
see [manual configuration](#manual-ai-client-configuration) below. For some popular clients, the `manage.sh`
CLI offers direct support through the following mechanism:

Every `run-devenv-agentic` regenerates three MCP-client config files with
the workspace's ports baked in; Codex is wired up at launch instead (see
below):

| Client | File | Loaded how |
| ------ | ---- | ---------- |
| Claude Code | `<workspace>/.devenv/mcp/claude-code.json` | `--mcp-config <file>` flag |
| opencode | `<workspace>/.devenv/mcp/opencode.json` | `OPENCODE_CONFIG=<file>` env var |
| VS Code Copilot | `<workspace>/.vscode/mcp.json` | Auto-discovered when opening the workspace |
| Codex CLI | _(none written)_ | Injected as `-c mcp_servers.…` overrides by `start-coding-agent codex` |

The files are committed templates + an `envsubst` pass; see
[`.devenv/README.md`](../../../.devenv/README.md) for the full layout.

`./manage.sh start-coding-agent <client> [--ws N] [...passthrough]`
launches the chosen client against one workspace, `cd`'ing into the right
directory and refusing to launch if the instance is not running:

```bash
./manage.sh start-coding-agent claude              # ws0
./manage.sh start-coding-agent opencode --ws 1     # ws1
./manage.sh start-coding-agent vscode              # opens VS Code on ws0
./manage.sh start-coding-agent codex --ws 2        # ws2
```

A given AI client session drives **exactly one workspace**, so running N
parallel workspaces typically means running N AI client sessions, each
pointed at a different workspace's ports.

What each launcher does:

* **Claude Code** is started with `--mcp-config .devenv/mcp/claude-code.json`,
  which is **additive** - your existing global Claude Code MCP entries stay
  available alongside the three entries we ship (Penpot MCP, Serena MCP,
  Playwright). To shadow one of our entries with a private one, install it
  under Claude Code's local scope (`claude mcp add --scope local …`); local
  wins over `--mcp-config`.
* **opencode** is started with `OPENCODE_CONFIG=.devenv/mcp/opencode.json`.
  opencode's precedence chain is *global → `OPENCODE_CONFIG` → project*, so
  the file we generate **overrides** entries with the same name in your
  global config. To override our entries in turn, drop a personal
  `opencode.json` at the repo root - it's gitignored on purpose.
* **VS Code Copilot** - `code "$workspace"` opens VS Code on the workspace.
  Copilot loads both your user-profile MCP config and the workspace's
  `.vscode/mcp.json`. That workspace file is **deep-merged** rather than
  overwritten: on ws0 (where it *is* the live repo's own file) any servers you
  added survive, and only our three (Penpot MCP, Serena MCP, Playwright) are
  rewritten to the current ports; on ws1+ it is created from scratch. To shadow
  one of ours, add a same-named entry in your user profile - it wins.
* **Codex CLI** - `codex` is exec'd from the workspace dir with our servers
  passed as `-c mcp_servers.<name>....` overrides built from the committed
  templates. **Nothing is written to `.codex/config.toml`**, so your own
  project- or user-level Codex config is left untouched (and no "trusted
  project" prompt is involved for our servers). Because `-c` is Codex's
  highest-precedence layer, our entries win over a same-named server in your
  config; to override one, append your own `-c` after the client name
  (`... start-coding-agent codex -- -c 'mcp_servers.penpot.url="…"'`) - the
  later `-c` wins.

## Manual AI-client configuration

The `start-coding-agent` launcher covers Claude Code, opencode, VS Code
Copilot, and the Codex CLI. For any other client (JetBrains AI Assistant,
Claude Desktop, Antigravity, …), or if you prefer to wire things up
yourself, configure the MCP servers in your client's native format using
the URLs below.

The Penpot and Serena URLs for the workspace you want to target are printed
by `manage.sh` every time it brings an instance up; copy them straight from
that output. The mechanical rule is `port = base + 10000 × N` for `wsN`,
with bases `4401` (Penpot MCP) and `14181` (Serena MCP). Playwright is not
workspace-scoped - it connects to your local browser, so the same entry
works for every client.

### Project-level MCP config files (Claude Code, opencode - manual path)

If you'd rather not go through the launcher, both Claude Code and opencode
support project-level MCP config files that **merge with** the developer's
global config:

* **Claude Code** reads `.mcp.json` at the project root. Local scope
  (`claude mcp add --scope local …`) overrides project scope.
* **opencode** reads `opencode.json` at the project root (or any ancestor
  Git directory). Configs are merged in the order *global → `OPENCODE_CONFIG`
  → project*.

The schemas differ between the two, so a workspace supporting both ships
both files. For multi-workspace work, edit the port numbers in each
workspace's copy once to match its offset.

### Example configuration

Below is a JSON-based configuration snippet in Claude Code's `mcpServers`
schema, targeting `ws0` (main), using `mcp-remote` to wrap HTTP-based
servers. To target a different workspace, substitute the Penpot MCP and
Serena MCP URLs with the ones for that workspace.

For clients using a different configuration format, extract the relevant
information (server URLs or launch commands) and configure the servers
appropriately, referring to your client's documentation.

```json
{
  "mcpServers": {
    "penpot-ws0": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:4401/mcp", "--allow-http" ]
    },
    "serena-ws0-devenv": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:14181/mcp", "--allow-http"]
    },
    "playwright": {
      "command": "npx",
      "args": ["@playwright/mcp@latest", "--cdp-endpoint=http://127.0.0.1:9222"]
    }
  }
}
```

**Penpot MCP Server**
* The URL above connects directly to the server in the DevEnv, which runs
  in single-user mode. You do not need to use the proxied URL or the user
  token that is provided by the Penpot UI.

**Serena MCP Server**
* The matching Serena dashboard lives on the next port (`14182` for ws0,
  `24182` for ws1, …) and is also printed by `manage.sh` on startup.

## Working on Development Tasks

After having made the configuration changes, restart your AI client. The
configured MCP servers should now be running and accessible to your client.

The agent's entrypoint for development is an activation of the `penpot`
project with Serena. Start by instructing your agent as follows,

> Activate project penpot.

and it should retrieve fundamental project information, expecting further
instructions on what to do.

**Always start your first prompt with these activation instructions**, as
this bootstraps the agent's context.

### Checking MCP Server Operability

To check if all integrations are working correctly, you can perform a series of tests.

1. Open Penpot in the debugging-enabled browser and open a design file.
2. Ask the agent to activate the project (Serena project activation):

   > Activate project penpot.

3. **Penpot MCP**
   * Checking the connection to the Penpot frontend:

     > Get an overview of the current page in Penpot by using the `execute_code` tool.

   * Checking the ClojureScript REPL:

     > Use the `cljs_repl` tool to check whether the Penpot frontend has crashed.

4. **Serena MCP**
   * Checking Serena's symbolic tools:

     > Use the `find_symbol` tool to find function `locate-shape` (cljs) and class `PenpotMcpServer` (ts)

* **Playwright MCP**
  * Checking the connection to the browser:

    > Use Playwright MCP server to find the Penpot browser tab.
