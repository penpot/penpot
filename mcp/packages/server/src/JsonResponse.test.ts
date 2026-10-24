import assert from "node:assert/strict";
import test from "node:test";
import { once } from "node:events";
import { WebSocket } from "ws";
import { PenpotMcpServer } from "./PenpotMcpServer";

for (const protocol of ["2025-03-26", "2026-07-28"]) {
    test(`opt-in JSON preserves ${protocol} payloads, owners and pending shutdown`, { timeout: 15000 }, async () => {
        const previous = { ...process.env };
        Object.assign(process.env, {
            PENPOT_MCP_SERVER_HOST: "127.0.0.1",
            PENPOT_MCP_SERVER_PORT: "0",
            PENPOT_MCP_WEBSOCKET_PORT: "0",
            PENPOT_MCP_JSON_RESPONSE: "true",
            PENPOT_MCP_DEVENV: "false",
            PENPOT_MCP_REPL_ENABLE: "false",
        });
        delete process.env.PENPOT_MCP_REDIS_URI;
        const server = new PenpotMcpServer(true);
        const plugins: WebSocket[] = [];
        let stopped = false;
        let onHeld: (() => void) | undefined;
        try {
            await server.start();
            const http = (server as any).httpServer;
            const url = `http://127.0.0.1:${http.address().port}/mcp`;
            const wsServer = (server.pluginBridge as any).wsServer;
            if (!wsServer.address()) await once(wsServer, "listening");
            for (const owner of ["alice", "bob"]) {
                const plugin = new WebSocket(`ws://127.0.0.1:${wsServer.address().port}/?userToken=${owner}`);
                plugins.push(plugin);
                const initialized = new Promise<void>((resolve) => {
                    plugin.on("message", (raw) => {
                        const task = JSON.parse(raw.toString());
                        if (task.type === "initialized") {
                            resolve();
                            return;
                        }
                        if (task.params.code === "__hold__") {
                            onHeld?.();
                            return;
                        }
                        plugin.send(
                            JSON.stringify({ id: task.id, success: true, data: { owner, code: task.params.code } })
                        );
                    });
                });
                await once(plugin, "open");
                plugin.send(
                    JSON.stringify({
                        type: "initialize",
                        session: { sessionId: owner, fileId: owner, fileName: owner },
                    })
                );
                await initialized;
            }
            const post = (id: number, owner?: string, code = `return ${id};`) =>
                fetch(`${url}${owner ? `?userToken=${owner}` : ""}`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        Accept: "application/json, text/event-stream",
                        "MCP-Protocol-Version": protocol,
                        ...(protocol === "2026-07-28"
                            ? { "Mcp-Method": "tools/call", "Mcp-Name": "execute_code" }
                            : {}),
                    },
                    body: JSON.stringify({
                        jsonrpc: "2.0",
                        id,
                        method: "tools/call",
                        params: {
                            name: "execute_code",
                            arguments: { code },
                            ...(protocol === "2026-07-28"
                                ? {
                                      _meta: {
                                          "io.modelcontextprotocol/protocolVersion": protocol,
                                          "io.modelcontextprotocol/clientInfo": { name: "regression", version: "1" },
                                          "io.modelcontextprotocol/clientCapabilities": {},
                                      },
                                  }
                                : {}),
                        },
                    }),
                    signal: AbortSignal.timeout(5000),
                });
            for (let id = 1; id <= 100; id++) {
                await Promise.all(
                    ["alice", "bob"].map(async (owner) => {
                        const response = await post(id, owner);
                        assert.equal(response.status, 200);
                        assert.match(response.headers.get("content-type")!, /^application\/json/);
                        assert.equal(response.headers.get("mcp-session-id"), null);
                        const reply: any = await response.json();
                        assert.equal(reply.id, id);
                        assert.notEqual(reply.result.isError, true);
                        assert.deepEqual(JSON.parse(reply.result.content[0].text), { owner, code: `return ${id};` });
                    })
                );
            }
            const denied: any = await (await post(301)).json();
            assert.equal(denied.id, 301);
            assert.match(denied.result.content[0].text, /^Tool execution failed:/);
            assert.match(JSON.stringify(denied), /authentication|userToken/);
            const held = new Promise<void>((resolve) => {
                onHeld = resolve;
            });
            const pending = post(400, "alice", "__hold__").then(
                async (response) => {
                    const reply: any = await response.json();
                    assert.ok(reply.error || reply.result?.isError, "shutdown cannot report success");
                },
                () => undefined
            );
            await held;
            // Leave the plugin connected: stop must release its socket and task itself.
            await Promise.race([
                server.stop(),
                new Promise((_, reject) =>
                    setTimeout(() => reject(new Error("pending JSON shutdown hung")), 1000).unref()
                ),
            ]);
            stopped = true;
            await pending;
            const bridge = server.pluginBridge as any;
            assert.equal(bridge.pendingTasks.size, 0);
            assert.equal(bridge.taskTimeouts.size, 0);
            assert.equal(bridge.taskConnections.size, 0);
        } finally {
            if (!stopped) await server.stop();
            for (const plugin of plugins) plugin.terminate();
            process.env = previous;
        }
    });
}

for (const flag of [undefined, "false"]) {
    test(`JSON flag ${flag ?? "absent"} preserves upstream legacy SSE`, async () => {
        const previous = { ...process.env };
        Object.assign(process.env, {
            PENPOT_MCP_SERVER_HOST: "127.0.0.1",
            PENPOT_MCP_SERVER_PORT: "0",
            PENPOT_MCP_WEBSOCKET_PORT: "0",
            PENPOT_MCP_DEVENV: "false",
            PENPOT_MCP_REPL_ENABLE: "false",
        });
        if (flag === undefined) delete process.env.PENPOT_MCP_JSON_RESPONSE;
        else process.env.PENPOT_MCP_JSON_RESPONSE = flag;
        delete process.env.PENPOT_MCP_REDIS_URI;
        const server = new PenpotMcpServer(true);
        try {
            await server.start();
            const port = (server as any).httpServer.address().port;
            const response = await fetch(`http://127.0.0.1:${port}/mcp`, {
                method: "POST",
                headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
                body: JSON.stringify({
                    jsonrpc: "2.0",
                    id: 1,
                    method: "initialize",
                    params: {
                        protocolVersion: "2025-03-26",
                        capabilities: {},
                        clientInfo: { name: "default", version: "1" },
                    },
                }),
                signal: AbortSignal.timeout(5000),
            });
            assert.equal(response.status, 200);
            assert.match(response.headers.get("content-type")!, /^text\/event-stream/);
            assert.match(await response.text(), /"id":1/);
        } finally {
            await server.stop();
            process.env = previous;
        }
    });
}
