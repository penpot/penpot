# Penpot MCP Demo Client

This is a demonstration project showing how to connect an external Model Context Protocol (MCP) client to Penpot's official MCP server.

When executed, the client will:
1. Establish a Streamable HTTP JSON-RPC connection to the MCP server.
2. Retrieve and log the list of registered tools.
3. Call `high_level_overview` to fetch Penpot-specific prompt guidelines.
4. Execute JavaScript code within Penpot to dynamically construct a beautiful, custom-styled dark-mode dashboard card on the active canvas page.

## Prerequisites

1. Node.js (v18+)
2. Penpot running locally (or in the development environment)
3. Penpot MCP Server and Plugin running and connected (see steps below)

## Quick Start Instructions

### Step 1: Start the MCP Server & Plugin
Ensure the MCP server and plugin are running. If you are inside the Penpot repository, from the `mcp/` directory:
```bash
pnpm run bootstrap
```
This starts:
- The MCP Server on port `4401`
- The Plugin Web Server on port `4400`

### Step 2: Connect the Plugin in Penpot
1. Open Penpot in your browser.
2. Go into any design file.
3. Under the **Plugins** menu, load the development plugin manifest from `http://localhost:4400/manifest.json`.
4. Open the plugin interface and click **"Connect to MCP server"**. Make sure the status changes to **"Connected to MCP server"**.

### Step 3: Run the Demo Client
Open a separate terminal window, navigate to the `mcp/packages/demo-client` directory, and run:
```bash
# Install dependencies
pnpm install

# Start the client
pnpm run start
```

Watch the console output for progress, and check your Penpot canvas to see the new **MCP Demo Dashboard** board and components materialize in real-time!
