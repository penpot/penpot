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

If you are using the latest Penpot release, e.g. as served on [design.penpot.app](https://design.penpot.app), run:
```shell
npx -y @penpot/mcp@latest
```

Once the servers are running, continue with step 2.

#### Running the Source Version from the Repository

The tools `corepack` and `npx` should be available in your terminal.

On Windows, use the Git Bash terminal to ensure compatibility with the provided scripts.

##### Clone the Appropriate Branch of the Repository 

Clone the Penpot repository, using the proper branch/tag depending on the
version of Penpot you want to use the MCP server with.  
For instance, to target the latest development version, use the `develop` branch:

```shell
git clone https://github.com/penpot/penpot.git --branch develop --depth 1
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

You can change the port by setting the `PENPOT_MCP_SERVER_PORT` environment variable
before starting the server. These endpoints can be used directly by MCP clients that support them.
Simply configure the client to connect the MCP server by providing the respective URL.

#### Configuring your client

You can configure your client with the [add-mcp](https://github.com/neon-solutions/add-mcp) helper.
Simply call 

    npx -y add-mcp -g -n penpot http://localhost:4401/mcp

and follow the interactive dialogue to configure the clients of your choice.
The config entry name is `penpot` (override it with `-n <name>`) and the URL points to the local http
endpoint (adjust the port if you changed `PENPOT_MCP_SERVER_PORT`).

When using a client that only supports stdio transport like **Claude Desktop**,
a proxy like [mcp-remote](https://github.com/geelen/mcp-remote) is required. 
More information on connecting your client follows below.

#### Using a Proxy for stdio Transport

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

The Penpot MCP server can also support multiple remote users simultaneously
in [multi-user mode](docs/multi-user-mode.md).

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
