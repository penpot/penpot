![mcp-server-cover-github-1](https://github.com/user-attachments/assets/dcd14e63-fecd-424f-9a50-c1b1eafe2a4f)

# Penpot's Official MCP Server

Penpot integrates a LLM layer built on the Model Context Protocol
(MCP) via Penpot's Plugin API to interact with a Penpot design
file. Penpot's MCP server enables LLMs to perform data queries,
transformation and creation operations.

Penpot's MCP Server is unlike any other you've seen. You get
design-to- design, code-to-design and design-code supercharged
workflows.


[![Penpot MCP video playlist](https://github.com/user-attachments/assets/204f1d99-ce51-41dd-a5dd-1ef739f8f089)](https://www.youtube.com/playlist?list=PLgcCPfOv5v57SKMuw1NmS0-lkAXevpn10)


## Architecture

The **Penpot MCP Server** exposes tools to AI clients (LLMs), which
support the retrieval of design data as well as the modification and
creation of design elements.  The MCP server communicates with Penpot
via the dedicated **Penpot MCP Plugin**,
which connects to the MCP server via WebSocket.  
This enables the LLM to carry out tasks in the context of a design file by 
executing code that leverages the Penpot Plugin API.
The LLM is free to write and execute arbitrary code snippets
within the Penpot Plugin environment to accomplish its tasks.

![Architecture](resources/architecture.png)

This repository thus contains not only the MCP server implementation itself
but also the supporting Penpot MCP Plugin 
(see section [Repository Structure](#repository-structure) below).

## Demonstration

[![Video](https://v32155.1blu.de/penpot/PenpotFest2025_thumbnail.png)](https://v32155.1blu.de/penpot/PenpotFest2025.mp4)


## Usage

To use the Penpot MCP server, you must
 * run the MCP server and connect your AI client to it,
 * run the web server providing the Penpot MCP plugin, and
 * open the Penpot MCP plugin in Penpot and connect it to the MCP server. 

Follow the steps below to enable the integration.


### Prerequisites

The project requires [Node.js](https://nodejs.org/) (tested with v22.x).

### 1. Starting the MCP Server and the Plugin Server

#### Running a Released Version via npx

The easiest way to launch the servers is to use `npx` to run the appropriate
version that matches your Penpot version.

* If you are using the latest Penpot release, e.g. as served on [design.penpot.app](https://design.penpot.app), run:
  ```shell
  npx -y @penpot/mcp@latest
  ```
* If you are participating in the MCP beta-test, which uses [test-mcp.penpot.dev](https://test-mcp.penpot.dev), run:
  ```shell
  npx -y @penpot/mcp@beta
  ```

Once the servers are running, continue with step 2.

#### Running the Source Version from the Repository

The tools `corepack` and `npx` should be available in your terminal.

On Windows, use the Git Bash terminal to ensure compatibility with the provided scripts.

##### Clone the Appropriate Branch of the Repository 

> [!IMPORTANT]
> The branches are subject to change in the future.  
> Be sure to check the instructions for the latest information on which branch to use.

Clone the Penpot repository, using the proper branch depending on the
version of Penpot you want to use the MCP server with.

  * For the current Penpot release 2.14, use the `mcp-prod-2.14.1` branch:

    ```shell
    git clone https://github.com/penpot/penpot.git --branch mcp-prod-2.14.1 --depth 1
    ```

  * For the MCP beta-test, use the `staging` branch:

    ```shell
    git clone https://github.com/penpot/penpot.git --branch staging --depth 1
    ```

Then change into the `mcp` directory:

```shell
cd penpot/mcp
```

##### Build & Launch the MCP Server and the Plugin Server

If it's your first execution, install the required dependencies.
(If you are using the Penpot devenv, this step is not necessary, as dependencies are already installed.)

```shell
./scripts/setup
```

Then build all components and start the two servers:

```shell
pnpm run bootstrap
```

This bootstrap command will:

  * install dependencies for all components
  * build all components
  * start all components

### 2. Load the Plugin in Penpot and Establish the Connection

> [!NOTE]
> **Browser Connectivity Restrictions**
>
> Starting with Chromium version 142, the private network access (PNA) restrictions have been hardened,
> and when connecting to `localhost` from a web application served from a different origin
> (such as https://design.penpot.app), the connection must explicitly be allowed.
>
> Most Chromium-based browsers (e.g. Chrome, Vivaldi) will display a popup requesting permission
> to access the local network. Be sure to approve the request to allow the connection.
>
> Some browsers take additional security measures, and you may need to disable them.
> For example, in Brave, disable the "Shield" for the Penpot website to allow local network access.
>
> If your browser refuses to connect to the locally served plugin, check its configuration or
> try a different browser (e.g. Firefox) that does not enforce these restrictions.

1. Open Penpot in your browser
2. Navigate to a design file
3. Open the Plugins menu
4. Load the plugin using the development URL (`http://localhost:4400/manifest.json` by default)
5. Open the plugin UI
6. In the plugin UI, click "Connect to MCP server".
   The connection status should change from "Not connected" to "Connected to MCP server".
   (Check the browser's developer console for WebSocket connection logs.
   Check the MCP server terminal for WebSocket connection messages.)

> [!IMPORTANT]
> Do not close the plugin's UI while using the MCP server, as this will close the connection.

### Optional: Auto-register with an MCP Client

Instead of editing client config files by hand, use the bundled installer:

```shell
# Interactive picker (arrow keys, Enter to confirm)
npx -y @penpot/mcp install

# Register with a specific client non-interactively
npx -y @penpot/mcp install --client claude-code
npx -y @penpot/mcp install --client claude-desktop
npx -y @penpot/mcp install --client cursor
npx -y @penpot/mcp install --client windsurf
npx -y @penpot/mcp install --client cline
npx -y @penpot/mcp install --client opencode
npx -y @penpot/mcp install --client gemini
npx -y @penpot/mcp install --client codex
npx -y @penpot/mcp install --client antigravity
npx -y @penpot/mcp install --client antigravity-cli
npx -y @penpot/mcp install --client generic-json

# Or register with every supported client at once
npx -y @penpot/mcp install --client all

# Preview the snippet without touching any file
npx -y @penpot/mcp install --client claude-desktop --dry-run

# Use a non-default server URL or rename the entry
npx -y @penpot/mcp install --client claude-code \
  --url http://localhost:4401/mcp --name penpot-dev

# Health-check the local setup
npx -y @penpot/mcp doctor

# Remove a previously installed entry
npx -y @penpot/mcp uninstall --client claude-code
```

Supported targets and their config locations:

| Client          | Config file                                                                                    | Transport            |
|-----------------|-------------------------------------------------------------------------------------------------|----------------------|
| `claude-code`   | `~/.claude.json` (or `claude mcp add` when the CLI is on PATH)                                  | HTTP                 |
| `claude-desktop`| `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) / `%APPDATA%/Claude/claude_desktop_config.json` (Windows) / `~/.config/Claude/claude_desktop_config.json` (Linux) | stdio via `mcp-remote` |
| `cursor`        | `~/.cursor/mcp.json`                                                                            | HTTP                 |
| `windsurf`      | `~/.codeium/windsurf/mcp_config.json`                                                           | HTTP                 |
| `cline`         | VSCode globalStorage `cline_mcp_settings.json`                                                  | HTTP                 |
| `opencode`      | `~/.config/opencode/opencode.jsonc`                                                             | HTTP (`type: remote`)|
| `gemini`        | Delegates to `gemini mcp add`                                                                   | HTTP                 |
| `codex`         | Delegates to `codex mcp add`                                                                    | HTTP                 |
| `antigravity`   | `~/.gemini/antigravity/mcp_config.json`                                                         | HTTP                 |
| `antigravity-cli` | `~/.gemini/config/mcp_config.json`                                                            | HTTP                 |
| `generic-json`  | Prints a `mcpServers` JSON snippet to stdout; copy into any MCP client manually                 | HTTP                 |

The installer makes a timestamped backup (e.g. `claude_desktop_config.json.bak-<ts>`) of any file it modifies, and refuses to overwrite an existing entry of the same name unless `--force` is passed.

### 3. Connect an MCP Client

> [!IMPORTANT]  
> **Use an appropriate model.**
> 
> We recommend that you ...
>   * use the most capable model at your disposal. 
>     You will achieve the best results with frontier models, 
>     especially when dealing with more complex tasks.
>     Weaker models, including most locally hosted ones, 
>     are unlikely to produce usable results for anything beyond simple tasks.
>   * use a vision language model (VLM), as many design tasks necessitate visual
>     inspection. 
>     (If you are using a standard commercial model, it almost certainly supports vision already.)

By default, the server runs on port 4401 and provides:

- **Modern Streamable HTTP endpoint**: `http://localhost:4401/mcp`
- **Legacy SSE endpoint**: `http://localhost:4401/sse`

These endpoints can be used directly by MCP clients that support them.
Simply configure the client to connect the MCP server by providing the respective URL.

When using a client that only supports stdio transport,
a proxy like `mcp-remote` is required.

#### Using a Proxy for stdio Transport

NOTE: only relevant if you are executing this outside of devenv

The `mcp-remote` package can proxy stdio transport to HTTP/SSE, 
allowing clients that support only stdio to connect to the MCP server indirectly.
Use it to provide the launch command for your MCP client as follows:

        npx -y mcp-remote http://localhost:4401/mcp --allow-http

#### Example: Claude Desktop

For Windows and macOS, there is the official [Claude Desktop app](https://claude.ai/download), which you can use as an MCP client.
For Linux, there is an [unofficial community version](https://github.com/aaddrick/claude-desktop-debian).

Since Claude Desktop natively supports only stdio transport, you will need to use a proxy like `mcp-remote`.
Install it as described above.

To add the server to Claude Desktop's configuration, locate the configuration file (or find it via Menu / File / Settings / Developer):

- **Windows**: `%APPDATA%/Claude/claude_desktop_config.json`
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

Add a `penpot` entry under `mcpServers` with the following content: 

```json
{
    "mcpServers": {
        "penpot": {
            "command": "npx",
            "args": ["-y", "mcp-remote", "http://localhost:4401/mcp", "--allow-http"]
        }
    }
}
```

After updating the configuration file, restart Claude Desktop completely for the changes to take effect.

> [!IMPORTANT] 
> Be sure to fully quit the app for the changes to take effect; closing the window is *not* sufficient.   
> To fully terminate the app, choose Menu / File / Quit.

After the restart, you should see the MCP server listed when clicking on the "Search and tools" icon at the bottom
of the prompt input area.

#### Example: Claude Code

To add the Penpot MCP server to a Claude Code project, issue the command

    claude mcp add penpot -t http http://localhost:4401/mcp

## Repository Structure

This repository is a monorepo containing four main components:

1. **Common Types** (`packages/common/`):
    - Shared TypeScript definitions for request/response protocol
    - Ensures type safety across server and plugin components

2. **Penpot MCP Server** (`packages/server/`):
    - Provides MCP tools to LLMs for Penpot interaction
    - Runs a WebSocket server accepting connections from the Penpot MCP plugin
    - Implements request/response correlation with unique task IDs
    - Handles task timeouts and proper error reporting

3. **Penpot MCP Plugin** (`packages/plugin/`):
    - Connects to the MCP server via WebSocket
    - Executes tasks in Penpot using the Plugin API
    - Sends structured responses back to the server#

4. **Types Generator** (`types-generator/`):
    - Generates data on API types for the MCP server (development use)

The core components are written in TypeScript, rendering interactions with the
Penpot Plugin API both natural and type-safe.

## Configuration

The Penpot MCP server can be configured using environment variables.

### Server Configuration

| Environment Variable                             | Description                                                                | Default        |
|--------------------------------------------------|----------------------------------------------------------------------------|----------------|
| `PENPOT_MCP_SERVER_HOST`                         | Address on which the MCP server listens (binds to)                         | `localhost`    |
| `PENPOT_MCP_SERVER_PORT`                         | Port for the HTTP/SSE server                                               | `4401`         |
| `PENPOT_MCP_WEBSOCKET_PORT`                      | Port for the WebSocket server (plugin connection)                          | `4402`         |
| `PENPOT_MCP_REPL_PORT`                           | Port for the REPL server (development/debugging)                           | `4403`         |
| `PENPOT_MCP_REMOTE_MODE`                         | Enable remote mode (disables file system access). Set to `true` to enable. | `false`        |
| `PENPOT_MCP_DEVENV`                              | Enable Penpot development environment tools. Set to `true` to enable.      | `false`        |
| `PENPOT_MCP_EXPORT_SHAPE_MAX_PARALLEL_REQUESTS`  | Maximum number of parallel export shape requests (multi-user mode only).   | `0` (no limit) |
| `PENPOT_MCP_REDIS_URI`                           | Redis connection URI (e.g. `redis://host:6379`) enabling multi-instance horizontal scaling via Redis pub/sub task routing (multi-user mode only). When unset, the server runs in single-instance mode, requiring the plugin and MCP client to connect to the same instance. | (unset)        |

### Hybrid PAT Mode (browser-less subset)

When a Penpot **Personal Access Token** is configured, the server exposes an extra
set of tools that talk directly to the Penpot REST/RPC API and do **not** require
the MCP plugin or a connected browser session. These tools are additive — the
browser-based `execute_code` flow stays available for everything that needs the
Plugin API runtime (rendering, selection state, CSS export, etc.).

| Environment Variable | Description                                                                                                       | Default                     |
|----------------------|-------------------------------------------------------------------------------------------------------------------|-----------------------------|
| `PENPOT_PAT`         | Personal Access Token issued from your Penpot account settings (Profile → Access tokens). Enables PAT-mode tools. | (unset)                     |
| `PENPOT_BASE_URL`    | Base URL of the target Penpot instance.                                                                           | `https://design.penpot.app` |

Prefer not exporting the token in your shell? Use the bundled `config set` subcommand to
persist it in a `0600` file under `~/.config/penpot-mcp/` instead — see
[Managing PAT and base URL with `config set`](#managing-pat-and-base-url-with-config-set)
below.

Tools enabled in PAT mode:

- `list_penpot_files` — enumerate projects and files accessible to the token without touching the plugin.

PAT mode does **not** unlock the following — they remain plugin-only because the
Plugin API runs inside the browser:

- Shape mutation with full validation (`execute_code` uses `naturalChildOrdering` + `throwValidationErrors`)
- Shape rendering / image export
- Selection, viewport and event listeners
- CSS generation, applied-token detection

#### Managing PAT and base URL with `config set`

The PAT and base URL can also be stored on disk so you do not have to set environment
variables every time you start the server:

```shell
# Interactive: prompts for the PAT (input hidden) and the base URL (pre-filled with the default)
penpot-mcp config set

# Non-interactive
penpot-mcp config set --pat <pat> --base-url https://design.penpot.app

# Inspect the resolved values
penpot-mcp config show

# Remove the stored file
penpot-mcp config clear
```

The file is written to `~/.config/penpot-mcp/config.json` (or
`%APPDATA%/penpot-mcp/config.json` on Windows) with `0600` permissions. Environment
variables (`PENPOT_PAT`, `PENPOT_BASE_URL`) always take precedence over the file when
both are present.

### Logging Configuration

| Environment Variable   | Description                                          | Default  |
|------------------------|------------------------------------------------------|----------|
| `PENPOT_MCP_LOG_LEVEL` | Log level: `trace`, `debug`, `info`, `warn`, `error` | `info`   |
| `PENPOT_MCP_LOG_DIR`   | Directory for log files; file logging is enabled iff this is set to a non-empty value | (unset)  |

### Plugin Server Configuration

| Environment Variable                      | Description                                                                             | Default      |
|-------------------------------------------|-----------------------------------------------------------------------------------------|--------------|
| `PENPOT_MCP_PLUGIN_SERVER_HOST`           | Address on which the plugin web server listens (single address or comma-separated list) | (local only) |

## Beyond Local Execution

The above instructions describe how to run the MCP server and plugin server locally.
We are working on enabling remote deployments of the MCP server, particularly
in [multi-user mode](docs/multi-user-mode.md), where multiple Penpot users will
be able to connect to the same MCP server instance.

To run the server remotely (even for a single user),
you may set the following environment variables to configure the two servers
(MCP server & plugin server) appropriately:
 * `PENPOT_MCP_REMOTE_MODE=true`: This ensures that the MCP server is operating
   in remote mode, with local file system access disabled.
 * `PENPOT_MCP_SERVER_HOST` and `PENPOT_MCP_PLUGIN_SERVER_HOST`:
   Set these according to your requirements for remote connectivity.
   To bind all interfaces, use `0.0.0.0` (use caution in untrusted networks).


## Development

* The [contribution guidelines for Penpot](../CONTRIBUTING.md) apply
* Auto-formatting: Use `pnpm run fmt`
* Generating API type data: See [types-generator/README.md](types-generator/README.md)
* Versioning: Use `bash scripts/set-version` to set the version for the MCP package (in `package.json`).
  - Ensure that at least the major, minor and patch components of the version are always up-to-date.
  - The MCP plugin assumes that a mismatch between the MCP version and the Penpot version (as returned by the API) 
    indicates incompatibility, resulting in the display of a warning message in the plugin UI.
* Packaging and publishing: 
  1. Ensure release version is set correctly in package.json (call `bash scripts/set-version` to update it automatically)
  2. Create npm package: `bash scripts/pack` (creates `penpot-mcp-<version>.tgz` for publishing)
  3. Publish to npm: `npm publish penpot-mcp-<version>.tgz --access public`
