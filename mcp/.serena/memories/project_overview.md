# Penpot MCP Project Overview - Updated

## Purpose
This project is a Model Context Protocol (MCP) server for Penpot integration. 
The MCP server communicates with a Penpot plugin via WebSockets, allowing
the MCP server to send tasks to the plugin and receive results, 
enabling advanced AI-driven features in Penpot.

## Tech Stack
- **Language**: TypeScript
- **Runtime**: Node.js
- **Framework**: MCP SDK (@modelcontextprotocol/sdk)
- **Build Tool**: TypeScript Compiler (tsc) + esbuild
- **Package Manager**: pnpm
- **WebSocket**: ws library for real-time communication

## Project Structure
```
/ (project root)
├── packages/common/           # Shared type definitions
│   ├── src/
│   │   ├── index.ts           # Exports for shared types
│   │   └── types.ts           # PluginTaskResult, request/response interfaces
│   └── package.json           # @penpot-mcp/common package
├── packages/server/           # Main MCP server implementation
│   ├── src/
│   │   ├── index.ts           # Main server entry point
│   │   ├── PenpotMcpServer.ts # Enhanced with request/response correlation
│   │   ├── PluginTask.ts      # Now supports result promises
│   │   ├── tasks/             # PluginTask implementations
│   │   └── tools/             # Tool implementations
|   ├── data/                  # Contains resources, such as API info and prompts
│   └── package.json           # Includes @penpot-mcp/common dependency
├── packages/plugin/           # Penpot plugin with response capability
│   ├── src/
│   │   ├── main.ts            # Enhanced WebSocket handling with response forwarding
│   │   └── plugin.ts          # Now sends task responses back to server
│   └── package.json           # Includes @penpot-mcp/common dependency
└── prepare-api-docs           # Python project for the generation of API docs
```

## Key Tasks

### Adjusting the System Prompt

The system prompt file is located in `packages/server/data/initial_instructions.md`. 

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
