![mcp-server-cover-github-1](https://github.com/user-attachments/assets/dcd14e63-fecd-424f-9a50-c1b1eafe2a4f)

# Penpot's Official MCP Server

Penpot integrates a LLM layer built on the Model Context Protocol
(MCP) via Penpot's Plugin API to interact with a Penpot design
file. Penpot's MCP server enables LLMs to perfom data queries,
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
Following the installation of Node.js, the tools `corepack` and `npx`
should be available in your terminal.

On Windows, use the Git Bash terminal to ensure compatibility with the provided scripts.

### 0. Clone the Appropriate Branch of the Repository 

> [!IMPORTANT]
> The branches are subject to change in the future.  
> Be sure to check the instructions for the latest information on which branch to use.

Clone the Penpot repository, using the proper branch depending on the
version of Penpot you want to use the MCP server with.

  * For released versions of Penpot, use the `mcp-prod` branch:

    ```shell
    git clone https://github.com/penpot/penpot.git --branch mcp-prod --depth 1
    ```

  * For the latest development version of Penpot, use the `develop` branch:

    ```shell
    git clone https://github.com/penpot/penpot.git --branch develop --depth 1
    ```

Then change into the `mcp` directory:

```shell
cd penpot/mcp
```

### 1. Build & Launch the MCP Server and the Plugin Server

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

1. Install `mcp-remote` globally if you haven't already:

        npm install -g mcp-remote

2. Use `mcp-remote` to provide the launch command for your MCP client:

        npx -y mcp-remote http://localhost:4401/sse --allow-http

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
            "args": ["-y", "mcp-remote", "http://localhost:4401/sse", "--allow-http"]
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

| Environment Variable               | Description                                                                | Default      |
|------------------------------------|----------------------------------------------------------------------------|--------------|
| `PENPOT_MCP_SERVER_LISTEN_ADDRESS` | Address on which the MCP server listens (binds to)                         | `localhost`  |
| `PENPOT_MCP_SERVER_PORT`           | Port for the HTTP/SSE server                                               | `4401`       |
| `PENPOT_MCP_WEBSOCKET_PORT`        | Port for the WebSocket server (plugin connection)                          | `4402`       |
| `PENPOT_MCP_REPL_PORT`             | Port for the REPL server (development/debugging)                           | `4403`       |
| `PENPOT_MCP_SERVER_ADDRESS`        | Hostname or IP address via which clients can reach the MCP server          | `localhost`  |
| `PENPOT_MCP_REMOTE_MODE`           | Enable remote mode (disables file system access). Set to `true` to enable. | `false`      |

### Logging Configuration

| Environment Variable   | Description                                          | Default  |
|------------------------|------------------------------------------------------|----------|
| `PENPOT_MCP_LOG_LEVEL` | Log level: `trace`, `debug`, `info`, `warn`, `error` | `info`   |
| `PENPOT_MCP_LOG_DIR`   | Directory for log files                              | `logs`   |

### Plugin Server Configuration

| Environment Variable                      | Description                                                                             | Default      |
|-------------------------------------------|-----------------------------------------------------------------------------------------|--------------|
| `PENPOT_MCP_PLUGIN_SERVER_LISTEN_ADDRESS` | Address on which the plugin web server listens (single address or comma-separated list) | (local only) |

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
 * `PENPOT_MCP_SERVER_LISTEN_ADDRESS` and `PENPOT_MCP_PLUGIN_SERVER_LISTEN_ADDRESS`:
   Set these according to your requirements for remote connectivity.
   To bind all interfaces, use `0.0.0.0` (use caution in untrusted networks).
 * `PENPOT_MCP_SERVER_ADDRESS=<your-address>`: This sets the hostname or IP address
   where the MCP server can be reached. The Penpot MCP Plugin uses this to construct
   the WebSocket URL as `ws://<your-address>:<port>` (default port: `4402`).

## Docker Deployment

You can run the Penpot MCP server as a Docker container alongside your existing
Penpot docker-compose stack. The container includes both the MCP server and
the plugin UI server.

### How It Works

```
MCP Client (e.g. Claude Code) ──(HTTPS/SSE)──► MCP Server (:4401)
                                                    ▲
                                                    │ WebSocket (:4402)
                                                    ▼
Browser (Penpot) ◄──────────────────────────── Plugin UI (:4400)
```

The MCP server acts as a bridge between your AI client and Penpot.
The Penpot MCP Plugin runs inside your browser within Penpot's UI and must
remain open while using the MCP server. The plugin communicates with the
MCP server via WebSocket to execute design operations.

### Prerequisites

- A running Penpot instance (docker-compose based)
- A reverse proxy (e.g. Traefik) for TLS termination (recommended for remote access)
- A DNS record for the MCP server (e.g. `mcp.yourdomain.com`)

### Files

The Docker setup requires two files in the `mcp/` directory, alongside the
existing source code:

- `Dockerfile` — multi-stage build (build + runtime)
- `entrypoint.sh` — starts the MCP server and plugin UI server

### Building

The plugin's WebSocket URL is baked in at build time. Set the `WS_URI` build
argument to match how your browser will reach the WebSocket endpoint:

```shell
# For localhost development
docker compose build penpot-mcp

# For production behind a reverse proxy
docker compose build --build-arg WS_URI="wss://mcp.yourdomain.com/ws" penpot-mcp
```

### docker-compose Service Definition

Add the following service to your `docker-compose.yml`:

```yaml
  penpot-mcp:
    build:
      context: ./mcp
      dockerfile: Dockerfile
      args:
        WS_URI: "wss://${PENPOT_MCP_DOMAIN_NAME}/ws"
    restart: always
    expose:
      - 4400
      - 4401
      - 4402
    networks:
      - penpot
      - proxy
    labels:
      - "traefik.enable=true"
      - "traefik.docker.network=proxy"

      # MCP SSE + HTTP endpoint (AI clients connect here)
      - "traefik.http.routers.penpot-mcp.rule=Host(`${PENPOT_MCP_DOMAIN_NAME}`)"
      - "traefik.http.routers.penpot-mcp.entrypoints=websecure"
      - "traefik.http.routers.penpot-mcp.tls=true"
      - "traefik.http.routers.penpot-mcp.tls.certresolver=tls_resolver"
      - "traefik.http.routers.penpot-mcp.service=penpot-mcp"
      - "traefik.http.routers.penpot-mcp.priority=1"
      - "traefik.http.services.penpot-mcp.loadbalancer.server.port=4401"

      # WebSocket (plugin in browser connects here)
      - "traefik.http.routers.penpot-mcp-ws.rule=Host(`${PENPOT_MCP_DOMAIN_NAME}`) && PathPrefix(`/ws`)"
      - "traefik.http.routers.penpot-mcp-ws.entrypoints=websecure"
      - "traefik.http.routers.penpot-mcp-ws.tls=true"
      - "traefik.http.routers.penpot-mcp-ws.tls.certresolver=tls_resolver"
      - "traefik.http.routers.penpot-mcp-ws.service=penpot-mcp-ws"
      - "traefik.http.routers.penpot-mcp-ws.priority=2"
      - "traefik.http.services.penpot-mcp-ws.loadbalancer.server.port=4402"

      # Plugin UI (load into Penpot's plugin manager)
      - "traefik.http.routers.penpot-mcp-plugin.rule=Host(`${PENPOT_MCP_DOMAIN_NAME}`) && PathPrefix(`/plugin`)"
      - "traefik.http.routers.penpot-mcp-plugin.entrypoints=websecure"
      - "traefik.http.routers.penpot-mcp-plugin.tls=true"
      - "traefik.http.routers.penpot-mcp-plugin.tls.certresolver=tls_resolver"
      - "traefik.http.routers.penpot-mcp-plugin.service=penpot-mcp-plugin"
      - "traefik.http.routers.penpot-mcp-plugin.priority=3"
      - "traefik.http.middlewares.mcp-plugin-strip.stripprefix.prefixes=/plugin"
      - "traefik.http.routers.penpot-mcp-plugin.middlewares=mcp-plugin-strip"
      - "traefik.http.services.penpot-mcp-plugin.loadbalancer.server.port=4400"

    environment:
      PENPOT_MCP_SERVER_HOST: "0.0.0.0"
      PENPOT_MCP_SERVER_PORT: "4401"
      PENPOT_MCP_WEBSOCKET_PORT: "4402"
      PENPOT_MCP_LOG_LEVEL: "info"
      PENPOT_MCP_REMOTE_MODE: "true"
```

Add to your `.env` file:

```
PENPOT_MCP_DOMAIN_NAME=mcp.yourdomain.com
```

### Connecting MCP Clients

#### Claude Code

Claude Code supports HTTP transport natively:

```shell
claude mcp add penpot -t http https://mcp.yourdomain.com/mcp
```

Or add to `~/.claude/settings.json` (or project `.claude/settings.json`):

```json
{
    "mcpServers": {
        "penpot": {
            "type": "sse",
            "url": "https://mcp.yourdomain.com/sse"
        }
    }
}
```

#### Claude Desktop

Since Claude Desktop only supports stdio transport, use `mcp-remote` as a proxy:

```json
{
    "mcpServers": {
        "penpot": {
            "command": "npx",
            "args": ["-y", "mcp-remote", "https://mcp.yourdomain.com/sse"]
        }
    }
}
```

### Loading the Plugin in Penpot

1. Open Penpot in your browser and navigate to a design file
2. Open the Plugins menu
3. Add the plugin URL: `https://mcp.yourdomain.com/plugin/manifest.json`
4. Open the plugin UI and click "Connect to MCP server"
5. The connection status should change to "Connected to MCP server"

> [!IMPORTANT]
> Do not close the plugin's UI while using the MCP server, as this will
> disconnect the WebSocket and the AI client will lose access to your designs.

### Without a Reverse Proxy

If you are running Penpot without a reverse proxy (e.g. for local development
with Docker), you can expose the ports directly instead:

```yaml
  penpot-mcp:
    build:
      context: ./mcp
      dockerfile: Dockerfile
      # Default WS_URI (ws://localhost:4402) works for localhost
    restart: always
    ports:
      - "4400:4400"
      - "4401:4401"
      - "4402:4402"
    networks:
      - penpot
    environment:
      PENPOT_MCP_SERVER_HOST: "0.0.0.0"
      PENPOT_MCP_SERVER_PORT: "4401"
      PENPOT_MCP_WEBSOCKET_PORT: "4402"
      PENPOT_MCP_LOG_LEVEL: "info"
      PENPOT_MCP_REMOTE_MODE: "true"
```

Then connect Claude Code with:

```shell
claude mcp add penpot -t http http://localhost:4401/mcp --allow-http
```

And load the plugin in Penpot using `http://localhost:4400/manifest.json`.

### Without the Plugin UI Server (Port 4400)

If you prefer to serve the plugin UI separately (e.g. from another web server
or CDN), you can skip port 4400 in the container. In that case:

1. Build the plugin locally: `cd packages/plugin && WS_URI="wss://mcp.yourdomain.com/ws" pnpm run build`
2. Deploy the contents of `packages/plugin/dist/` to any static file host
3. Point Penpot's plugin manager to that host's `manifest.json`
4. The Docker container only needs to expose ports 4401 and 4402

## Development

* The [contribution guidelines for Penpot](../CONTRIBUTING.md) apply
* Auto-formatting: Use `pnpm run fmt`
* Generating API type data: See [types-generator/README.md](types-generator/README.md)
* Packaging and publishing:
  - Create npm package: `bash scripts/pack` (sets version and then calls `npm pack`)