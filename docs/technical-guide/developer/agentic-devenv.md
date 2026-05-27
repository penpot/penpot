---
title: 3.11. Agentic Development Environment
desc: Dive into agentic Penpot development.
---

# Agentic Development Environment

The agentic DevEnv is an extension of the standard DevEnv
(the [general DevEnv instructions](/technical-guide/developer/devenv/) apply),
which is optimised for AI agent-based development,
adding additional tools and processes that support agentic automation.

The general workflow is as follows:

1. Start the agentic DevEnv.
2. Start a debugging-enabled browser and open Penpot, using a Penpot user with
   the remote MCP integration enabled.
3. Use an AI client (MCP client) which is connected to a suite of MCP servers
   to solve development tasks.

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

## Configuring and Starting the Agentic DevEnv

**First-Time Setup: Building the Image.** If you are starting the agentic DevEnv for the first time, you need to build
the updated docker image, adding support for agentic tools:

```bash
./manage.sh build-devenv --local
```

**Enable the Penpot MCP Connection in the Frontend.**
The agentic DevEnv relies on a connection between the Penpot frontend and the Penpot MCP server
being established automatically.
Edit the file `frontend/resources/public/js/config.js`,
creating it if it does not exist, and make sure the `penpotFlags` variable contains the
`enable-mcp` flag.

```javascript
var penpotFlags = "enable-mcp";
```

**Running the DevEnv in Agentic Mode.** Each invocation starts one instance.
`--ws N` (N≥1) auto-starts ws0 first if it's not already up — ws0 is the
worker-bearer and must be running whenever any ws1+ is:

```bash
./manage.sh run-devenv-agentic           # main (ws0)
./manage.sh run-devenv-agentic --ws 1    # ws0 if needed, then ws1
```

Starting an instance that is already running is an error. Per-instance ports
are offset by `10000 × N` (ws1's MCP at `http://localhost:14401/mcp`, Serena
MCP at `http://localhost:24181`, Serena dashboard at `http://localhost:24182`,
etc.). `manage.sh` prints the full per-instance URL set (Penpot UI, MCP
stream, Serena MCP, Serena dashboard, attach command) every time it brings an
instance up, so you don't need to compute the offsets by hand. See the
*Parallel workspaces* section in the [Dev environment guide](./devenv.md) for
the workspace and lifecycle details (including the `--sync` flag and shutdown
shape).

**Git identity for agent commits.** Coding agents typically need to commit
inside the devenv, so `run-devenv-agentic` wires a Git identity into the
container's global config on every bring-up. By default it propagates the
host's effective `git config user.{name,email}` (local repo override wins
over `~/.gitconfig`, matching what `git commit` on the host would record).
Override either with `--git-user-name "Full Name"` /
`--git-user-email you@example.com` — useful when you want agent commits to
carry an identity different from your normal one. Without either source the
script warns and proceeds; commits made by the agent will fail until you fix
it. See the
[Dev environment guide](./devenv.md#git-identity-inside-the-container) for
the full mechanics.

> **Note:** the MCP and Serena tmux windows are only added when the session is
> first created. If you've already run `./manage.sh run-devenv` (non-agentic)
> in an instance, `run-devenv-agentic` errors out because the instance is
> already running. Kill the session first to recreate with the agentic
> windows:
>
> ```bash
> docker exec penpot-devenv-ws0-main sudo -u penpot tmux kill-session -t penpot
> ./manage.sh stop-devenv
> ./manage.sh run-devenv-agentic
> ```

## Opening Penpot with Remote Debugging & MCP Enabled

**Enable Remote Debugging in Your Browser.**
Penpot needs to be opened in a browser that has remote debugging enabled.
In Chromium-based browsers (such as Google Chrome, Opera, Vivaldi, etc.),
this can be achieved by launching the browser with the `--remote-debugging-port` argument.
For most newer browsers, you will also need to specify a user data directory,
as using debugging with your regular browser profile is disallowed for security reasons.

```bash
google-chrome --remote-debugging-port=9222 --user-data-dir="$HOME/.chrome-debug-profile"
```

This enables the Playwright MCP server to connect to the browser and control it.
Verify that debugging was enabled correctly by navigating to `http://127.0.0.1:9222/json/version`.
If you change the port, adjust the MCP server configuration accordingly (see below).
Note: For security reasons, you should not enable remote debugging with a profile
that you use for regular browsing activities.

**Open Penpot with the MCP Integration Enabled.**
The Penpot instance in the DevEnv can be accessed at [https://localhost:3449](https://localhost:3449).
Once logged in, navigate to your account settings, click on "Integrations" in the sidebar, and enable the "MCP Server" toggle.
Note: You do not need to use the generated key (or the provided URL), as the MCP server in the agentic DevEnv is running in single-user mode and does not require authentication.

## Configuring Your AI Client

A given AI client session drives **exactly one workspace**. The Penpot and
Serena MCP servers it talks to live inside that workspace's `main` container
on workspace-specific ports — a client configured against ws0 cannot drive
ws1, and vice versa. Running N parallel workspaces therefore means
configuring (or launching) N AI clients, each pointed at a different
workspace's ports.

There are two ways to wire this up:

* **For Claude Code, opencode, VS Code Copilot, and the Codex CLI**, use
  `./manage.sh start-coding-agent`, which loads a per-workspace MCP config
  generated by the devenv tooling. See *Quick start* below.
* **For any other client** (JetBrains AI Assistant, Claude Desktop,
  Antigravity, …) — or if you'd rather configure the supported clients
  yourself — see *Manual configuration*.

### Quick start: `start-coding-agent`

Every time you bring a workspace up with `run-devenv-agentic`, `manage.sh`
regenerates four MCP config files with that workspace's ports baked in:

| Client | File | Loaded how |
| ------ | ---- | ---------- |
| Claude Code | `<workspace>/.devenv/mcp/claude-code.json` | `--mcp-config <file>` flag |
| opencode | `<workspace>/.devenv/mcp/opencode.json` | `OPENCODE_CONFIG=<file>` env var |
| VS Code Copilot | `<workspace>/.vscode/mcp.json` | Auto-discovered when opening the workspace |
| Codex CLI | `<workspace>/.codex/config.toml` | Auto-discovered from the project root (trusted projects only) |

The files are committed templates + an envsubst pass; see
[`.devenv/README.md`](../../../.devenv/README.md) for the full layout.

To launch a coding agent:

```bash
# Default target is ws0 (the live repo).
./manage.sh start-coding-agent claude              [...args forwarded to claude]
./manage.sh start-coding-agent opencode            [...args forwarded to opencode]
./manage.sh start-coding-agent vscode              [...args forwarded to 'code']
./manage.sh start-coding-agent codex               [...args forwarded to codex]

# Target a specific parallel workspace with --ws N (integer only).
./manage.sh start-coding-agent claude --ws 1       # drives ws1
./manage.sh start-coding-agent opencode --ws 2     # drives ws2
```

The launcher `cd`'s into the target workspace before exec'ing the client, so
config files are resolved from the right directory regardless of where you
invoke `manage.sh` from. It refuses to launch if the target instance's
`main` container is not running — the Penpot and Serena MCP servers only
exist while the devenv is up. Bring the instance up first with
`./manage.sh run-devenv-agentic --ws N`, then retry.

`--ws` accepts a non-negative integer only (`--ws 0`, `--ws 1`, …). Spellings
like `--ws main` or `--ws ws1` are rejected so the flag shape stays uniform
across `attach-devenv`, `run-devenv-agentic`, `stop-devenv`, and
`start-coding-agent`.

What each launcher does:

* **Claude Code** is started with `--mcp-config .devenv/mcp/claude-code.json`,
  which is **additive** — your existing global Claude Code MCP entries stay
  available alongside the three entries we ship (Penpot MCP, Serena MCP,
  Playwright). To shadow one of our entries with a private one, install it
  under Claude Code's local scope (`claude mcp add --scope local …`); local
  wins over `--mcp-config`.
* **opencode** is started with `OPENCODE_CONFIG=.devenv/mcp/opencode.json`.
  opencode's precedence chain is *global → `OPENCODE_CONFIG` → project*, so
  the file we generate **overrides** entries with the same name in your
  global config. To override our entries in turn, drop a personal
  `opencode.json` at the repo root — it's gitignored on purpose.
* **VS Code Copilot** — `code "$PWD"` opens VS Code on the current workspace.
  Copilot loads both your user-profile MCP config and the workspace's
  `.vscode/mcp.json`, so our entries land alongside whatever you have
  globally. Same-name entries can be shadowed via your user profile.
* **Codex CLI** — `codex` is exec'd from `$PWD`. Codex auto-discovers
  `.codex/config.toml` at the project root, but **only for "trusted"
  projects**; the first launch in a workspace will prompt you to trust it.
  Entries in `~/.codex/config.toml` override the project file on name
  conflict.

### Manual configuration

The `start-coding-agent` launcher covers Claude Code and opencode. For any
other client, or if you prefer to wire things up yourself, configure the
MCP servers in your client's native config format using the URLs below.

The Penpot and Serena URLs for the workspace you want to target are printed
by `manage.sh` every time it brings an instance up; copy them straight from
that output. The mechanical rule is `port = base + 10000 × N` for `wsN`, with
bases `4401` (Penpot MCP) and `14181` (Serena MCP). Playwright is not
workspace-scoped — it connects to your local browser, so the same entry works
for every client.

#### Project-level MCP config files (Claude Code, opencode — manual path)

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

#### Example configuration

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
* The URL above connects directly to the server in the DevEnv, which runs in single-user mode.
  You do not need to use the proxied URL or the user token that is provided by the Penpot UI.

**Serena MCP Server**
* The matching Serena dashboard lives on the next port (`14182` for ws0, `24182` for ws1, …) and is also printed by `manage.sh` on startup.

## Working on Development Tasks

After having made the configuration changes, restart your AI client.
The configured MCP servers should now be running and accessible to your client.

The agent's entrypoint for development is an activation of the `penpot` project with Serena.
Start by instructing your agent as follows,

> Activate project penpot.

and it should retrieve fundamental project information,
expecting further instructions on what to do.

**Always start your first prompt with these activation instructions**, as this bootstraps the agent's context.

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
