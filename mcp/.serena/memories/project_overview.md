# Penpot MCP Project Overview - Updated

## Purpose
This project is a Model Context Protocol (MCP) server for Penpot integration. It provides a TypeScript-based server that can be used to extend Penpot's functionality through custom tools with bidirectional WebSocket communication.

## Tech Stack
- **Language**: TypeScript
- **Runtime**: Node.js
- **Framework**: MCP SDK (@modelcontextprotocol/sdk)
- **Build Tool**: TypeScript Compiler (tsc) + esbuild
- **Package Manager**: pnpm
- **WebSocket**: ws library for real-time communication

## Project Structure
```
penpot-mcp/
├── common/                    # Shared type definitions
│   ├── src/
│   │   ├── index.ts           # Exports for shared types
│   │   └── types.ts           # PluginTaskResult, request/response interfaces
│   └── package.json           # @penpot-mcp/common package
├── mcp-server/                # Main MCP server implementation
│   ├── src/
│   │   ├── index.ts           # Main server entry point
│   │   ├── PenpotMcpServer.ts # Enhanced with request/response correlation
│   │   ├── PluginTask.ts      # Now supports result promises
│   │   ├── tasks/             # PluginTask implementations
│   │   └── tools/             # Tool implementations
│   └── package.json           # Includes @penpot-mcp/common dependency
├── penpot-plugin/             # Penpot plugin with response capability
│   ├── src/
│   │   ├── main.ts            # Enhanced WebSocket handling with response forwarding
│   │   └── plugin.ts          # Now sends task responses back to server
│   └── package.json           # Includes @penpot-mcp/common dependency
└── prepare-api-docs           # Python project for the generation of API docs
```

## Key Tasks

### Adding a new Tool

1. Implement the tool class in `mcp-server/src/tools/` following the `Tool` interface. 
   IMPORTANT: Do not catch any exceptions in the `executeCore` method. Let them propagate to be handled centrally.
2. Register the tool in `PenpotMcpServer`.

Look at `PrintTextTool` as an example.

Many tools are linked to tasks that are handled in the plugin, i.e. they have an associated `PluginTask` implementation in `mcp-server/src/tasks/`.

### Adding a new PluginTask

1. Implement the input data interface for the task in `common/src/types.ts`.
2. Implement the `PluginTask` class in `mcp-server/src/tasks/`.
3. Implement the corresponding task handler class in the plugin (`penpot-plugin/src/task-handlers/`).
    * In the success case, call `task.sendSuccess`.
    * In the failure case, just throw an exception, which will be handled centrally!
    * Look at `PrintTextTaskHandler` as an example.
4. Register the task handler in `penpot-plugin/src/plugin.ts` in the `taskHandlers` list.


## Key Components

### Enhanced WebSocket Protocol
- **Request Format**: `{id: string, task: string, params: any}`
- **Response Format**: `{id: string, result: {success: boolean, error?: string, data?: any}}`
- **Request/Response Correlation**: Using unique UUIDs for task tracking
- **Timeout Handling**: 30-second timeout with automatic cleanup
- **Type Safety**: Shared definitions via @penpot-mcp/common package

### Core Classes
- **PenpotMcpServer**: Enhanced with pending task tracking and response handling
- **PluginTask**: Now creates result promises that resolve when plugin responds
- **Tool implementations**: Now properly await task completion and report results
- **Plugin handlers**: Send structured responses back to server

### New Features
1. **Bidirectional Communication**: Plugin now responds with success/failure status
2. **Task Result Promises**: Every executePluginTask() sets and returns a promise
3. **Error Reporting**: Failed tasks properly report error messages to tools
4. **Shared Type Safety**: Common package ensures consistency across projects
5. **Timeout Protection**: Tasks don't hang indefinitely (30s limit)
6. **Request Correlation**: Unique IDs match requests to responses

## Task Flow

```
LLM Tool Call → MCP Server → WebSocket (Request) → Plugin → Penpot API
                    ↑                                  ↓
             Tool Response ← MCP Server ← WebSocket (Response) ← Plugin Result
```

