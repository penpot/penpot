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
/ (project root)
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

From the project root directory, run

* `pnpm run build` to test the build of all package
* `pnpm run fmt` to apply the auto-formatter
