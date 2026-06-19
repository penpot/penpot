# Penpot MCP

This subproject provides an MCP server for Penpot integration.
The MCP server communicates with a Penpot plugin via WebSockets, allowing
the MCP server to send tasks to the plugin and receive results,
enabling advanced AI-driven features in Penpot.

## Tech Stack

- Language: TypeScript
- Runtime: Node.js
- Framework: MCP SDK (@modelcontextprotocol/sdk)
- Build Tool: TypeScript Compiler (tsc) + esbuild
- Package Manager: pnpm

## General Principles

IMPORTANT: Use an idiomatic, object-oriented style.
In particular, this implies that, for any non-trivial interfaces, you use interfaces that expect explicitly typed abstractions
rather than mere functions (i.e. use the strategy pattern, for example).

Comments:
When describing parameters, methods/functions and classes, you use a precise style, where the initial (elliptical) phrase
clearly defines *what* it is. Any details then follow in subsequent sentences.

When describing what blocks of code do, you also use an elliptical style and start with a lower-case letter unless
the comment is a lengthy explanation with at least two sentences (in which case you start with a capital letter, as is
required for sentences).

## Project Structure (Excerpt)

```
mcp/
├── packages/common/           # Shared type definitions
│   ├── src/
│   │   ├── index.ts           # exports for shared types
│   │   └── types.ts           # PluginTaskResult, request/response interfaces
│   └── package.json           # @penpot-mcp/common package
├── packages/server/           # MCP server subproject
│   ├── src/
│   │   ├── index.ts           # entry point
│   │   ├── PenpotMcpServer.ts # MCP server implementation (connection handling, tool registration, etc.)
│   │   ├── Tool.ts            # base class for tools
│   │   ├── PluginTask.ts      # base class for plugin tasks
│   │   ├── tasks/             # PluginTask implementations
│   │   └── tools/             # Tool implementations
|   ├── data/                  # contains resources, such as API info and prompts
│   └── package.json           
├── packages/plugin/           # Penpot plugin subproject
│   ├── src/
│   │   ├── main.ts            # handles communication
│   │   └── plugin.ts          # plugin implementation
│   └── package.json           # Includes @penpot-mcp/common dependency
└── prepare-api-docs           # Python project for the generation of API docs
```

## Key Development Tasks

### Adjusting the Prompts

The system prompt file (aka Penpot High-Level Overview) is located in 
`packages/server/data/initial_instructions.md`.

### Adding a new Tool

1. Implement the tool class in `packages/server/src/tools/` following the `Tool` interface.
   IMPORTANT: Do not catch any exceptions in the `executeCore` method. Let them propagate to be handled centrally.
2. Register the tool in `PenpotMcpServer`.

Tools can be associated with a `PluginTask` that is executed in the plugin.
Many tools build on `ExecuteCodePluginTask`, as many operations can be reduced to code execution.

### Adding a new PluginTask

1. Implement the input data interface for the task in `packages/common/src/types.ts`.
2. Implement the `PluginTask` class in `packages/server/src/tasks/`.
3. Implement the corresponding task handler class in the plugin (`packages/plugin/src/task-handlers/`).
    * In the success case, call `task.sendSuccess`.
    * In the failure case, just throw an exception, which will be handled centrally!
4. Register the task handler in `packages/plugin/src/plugin.ts` in the `taskHandlers` list.

## Dev Tooling

From the `mcp/` directory, run

* `pnpm run build` to test the build of all packages
* `pnpm run fmt` to apply the auto-formatter

## Devenv plugin/server wiring

In the normal Penpot devenv MCP path, the browser plugin does not discover or route through Postgres. The frontend provides the plugin extension API with `mcp.getServerUrl()`, currently derived from `frontend/src/app/config.cljs` as `penpotMcpServerURI` if set, otherwise `<public-uri>/mcp/ws`. The MCP plugin opens a direct WebSocket to that URL and appends the current MCP access token as a query parameter.

The live plugin connection registry is in-memory inside each MCP server process (`PluginBridge.connectedClients` / `clientsByToken`). The database only stores MCP access tokens and profile props such as `mcp-enabled`; it does not manage which plugin is connected to which MCP server.

For parallel devenvs, prefer same-origin MCP routing: each Penpot instance should expose `/mcp/ws` through its own nginx/Caddy path to the MCP server running inside the same main container. Keep container-internal ports fixed (MCP defaults `4401/4402/4403`, backend/exporter/frontend defaults, etc.) and only offset host-side published ports per instance. If internal ports are offset, hardcoded local proxy config such as `docker/devenv/files/nginx.conf` will misroute unless templated too.
