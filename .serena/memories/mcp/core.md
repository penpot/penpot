# Penpot MCP

- Start at `mcp/README.md` for setup, server modes and release commands. The public high-level overview lives in `mcp/packages/server/data/initial_instructions.md`; API data generation is documented in `mcp/types-generator/README.md`.
- Plugin WebSocket, session ownership and reconnect: `mem:mcp/connections`. Save results and rollback delivery: `mem:mcp/save-and-rollback`. Remote client authorization and discovery: `mem:mcp/oauth`.

## Tools and tasks

- Keep each concern in one class or component with a small public surface. Prefer explicitly typed abstractions for non-trivial collaborators and concrete classes over interface-only object literals. Use braces even for single-line blocks. Start doc comments with a precise descriptive phrase; short inline comments start lowercase, longer explanations use sentences.
- Extend `Tool` and register the tool in `PenpotMcpServer`. Let errors from `executeCore` reach the central handler. For a plugin task, keep input types in `packages/common/src/types.ts`, the server task in `packages/server/src/tasks/`, and the handler in `packages/plugin/src/task-handlers/`. Register the handler in `plugin.ts`; call `task.sendSuccess` on success and let failures propagate.
- For direct backend tools, start at `PenpotMcpServer` and `PenpotBackendClient`. Keep the tool schema aligned with the authoritative backend schema. For `submit_feedback`, keep the payload out of argument logs and distinguish a queued mail job from delivery.

## Local checks

- See `mem:scripts/ci` and `mem:testing`. Server type-checks read the built `mcp-common` package; rebuild it with `pnpm --filter mcp-common run build` from `mcp/` before diagnosing missing exports.
- Plugin tests use `ts.transpileModule`; do not upgrade past TypeScript 6 without replacing that dependency in the test harness. The Node test runner's `--experimental-strip-types` also rejects parameter properties: declare fields explicitly.
- Keep Pino transports disabled under `NODE_TEST_CONTEXT`; the transport worker can keep a child test process alive after its tests finish.
- The plugin uses published `@penpot/plugin-styles` but links `@penpot/plugin-types` from the workspace for `penpotMgmt`. Do not replace the link with a registry pin during a version bump. Verify that regenerating the lockfile produces no additional diff.
