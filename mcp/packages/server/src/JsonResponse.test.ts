import assert from "node:assert/strict";
import test from "node:test";
import { once } from "node:events";
import { WebSocket } from "ws";
import { PenpotMcpServer } from "./PenpotMcpServer";

const MODERN_PROTOCOL = "2026-07-28";

let nextPort = 16_700;

/**
 * Starts a multi-user server on fresh loopback ports with the given `PENPOT_MCP_JSON_RESPONSE` value
 * (`undefined` removes the variable) and returns it together with a function restoring the environment.
 */
async function startServer(
    jsonResponse: string | undefined
): Promise<{ server: PenpotMcpServer; restoreEnv: () => void }> {
    const previousEnv = { ...process.env };
    Object.assign(process.env, {
        PENPOT_MCP_SERVER_HOST: "127.0.0.1",
        PENPOT_MCP_SERVER_PORT: String(nextPort++),
        PENPOT_MCP_WEBSOCKET_PORT: String(nextPort++),
        PENPOT_MCP_DEVENV: "false",
        PENPOT_MCP_REPL_ENABLE: "false",
    });
    if (jsonResponse === undefined) {
        delete process.env.PENPOT_MCP_JSON_RESPONSE;
    } else {
        process.env.PENPOT_MCP_JSON_RESPONSE = jsonResponse;
    }
    delete process.env.PENPOT_MCP_REDIS_URI;
    delete process.env.PENPOT_MCP_AUTH_URI;
    const server = new PenpotMcpServer(true);
    await server.start();
    return { server, restoreEnv: () => (process.env = previousEnv) };
}

/**
 * Returns the protocol-specific headers and `_meta` parameters of a `tools/call` request.
 */
function protocolFields(protocol: string): { headers: Record<string, string>; params: Record<string, unknown> } {
    if (protocol !== MODERN_PROTOCOL) {
        return { headers: {}, params: {} };
    }
    return {
        headers: { "Mcp-Method": "tools/call", "Mcp-Name": "execute_code" },
        params: {
            _meta: {
                "io.modelcontextprotocol/protocolVersion": protocol,
                "io.modelcontextprotocol/clientInfo": { name: "regression", version: "1" },
                "io.modelcontextprotocol/clientCapabilities": {},
            },
        },
    };
}

/**
 * Connects a synthetic plugin for the given owner which echoes each task's code and holds tasks with code `__hold__`.
 */
async function connectPlugin(server: PenpotMcpServer, owner: string, onHeld: () => void): Promise<WebSocket> {
    const plugin = new WebSocket(`ws://127.0.0.1:${server.webSocketPort}/?userToken=${owner}`);
    const initialized = new Promise<void>((resolve) => {
        plugin.on("message", (raw) => {
            const task = JSON.parse(raw.toString());
            if (task.type === "initialized") {
                resolve();
                return;
            }
            if (task.params.code === "__hold__") {
                onHeld();
                return;
            }
            plugin.send(JSON.stringify({ id: task.id, success: true, data: { owner, code: task.params.code } }));
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
    return plugin;
}

/**
 * Asserts that the plugin bridge no longer tracks any task.
 */
function assertNoTrackedTasks(server: PenpotMcpServer): void {
    const bridge = server.pluginBridge as any;
    assert.equal(bridge.pendingTasks.size, 0);
    assert.equal(bridge.taskTimeouts.size, 0);
    assert.equal(bridge.taskConnections.size, 0);
}

for (const protocol of ["2025-03-26", MODERN_PROTOCOL]) {
    test(`opt-in JSON preserves ${protocol} payloads, owners and pending shutdown`, { timeout: 60000 }, async () => {
        const { server, restoreEnv } = await startServer("true");
        const plugins: WebSocket[] = [];
        let stopped = false;
        let onHeld = () => {};
        try {
            for (const owner of ["alice", "bob"]) {
                plugins.push(await connectPlugin(server, owner, () => onHeld()));
            }
            const fields = protocolFields(protocol);
            const post = (id: number, owner?: string, code = `return ${id};`) =>
                fetch(`http://127.0.0.1:${server.port}/mcp${owner ? `?userToken=${owner}` : ""}`, {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json",
                        Accept: "application/json, text/event-stream",
                        "MCP-Protocol-Version": protocol,
                        ...fields.headers,
                    },
                    body: JSON.stringify({
                        jsonrpc: "2.0",
                        id,
                        method: "tools/call",
                        params: { name: "execute_code", arguments: { code }, ...fields.params },
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
            // a dropped connection rejects this fetch and thereby fails the test
            const pending = post(400, "alice", "__hold__");
            await held;
            // leave the plugin connected: stop must release its socket and task itself
            await Promise.race([
                server.stop(),
                new Promise((_, reject) =>
                    setTimeout(() => reject(new Error("pending JSON shutdown hung")), 5000).unref()
                ),
            ]);
            stopped = true;
            const response = await pending;
            assert.equal(response.status, 200);
            const reply: any = await response.json();
            assert.equal(reply.id, 400);
            assert.equal(reply.result.isError, true, "shutdown cannot report success");
            assert.match(reply.result.content[0].text, /shutting down; task outcome may be unknown/);
            assertNoTrackedTasks(server);
        } finally {
            if (!stopped) {
                await server.stop();
            }
            for (const plugin of plugins) {
                plugin.terminate();
            }
            restoreEnv();
        }
    });
}

for (const flag of [undefined, "false"]) {
    test(`JSON flag ${flag ?? "absent"} preserves upstream legacy SSE`, async () => {
        const { server, restoreEnv } = await startServer(flag);
        try {
            const response = await fetch(`http://127.0.0.1:${server.port}/mcp`, {
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
            restoreEnv();
        }
    });
}

for (const flag of [undefined, "true"]) {
    test(`JSON flag ${flag ?? "absent"} rejects legacy GET and DELETE`, async () => {
        const { server, restoreEnv } = await startServer(flag);
        try {
            for (const method of ["GET", "DELETE"]) {
                const response = await fetch(`http://127.0.0.1:${server.port}/mcp`, {
                    method,
                    headers: { Accept: "application/json, text/event-stream", "MCP-Protocol-Version": "2025-03-26" },
                    signal: AbortSignal.timeout(5000),
                });
                assert.equal(response.status, 405, `${method} status`);
                await response.body?.cancel();
            }
        } finally {
            await server.stop();
            restoreEnv();
        }
    });
}

test("default SSE shutdown delivers the unknown outcome of a pending tool call", { timeout: 15000 }, async () => {
    const { server, restoreEnv } = await startServer(undefined);
    let plugin: WebSocket | undefined;
    let stopped = false;
    try {
        let onHeld = () => {};
        const held = new Promise<void>((resolve) => {
            onHeld = resolve;
        });
        plugin = await connectPlugin(server, "alice", () => onHeld());
        const pending = fetch(`http://127.0.0.1:${server.port}/mcp?userToken=alice`, {
            method: "POST",
            headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
            body: JSON.stringify({
                jsonrpc: "2.0",
                id: 7,
                method: "tools/call",
                params: { name: "execute_code", arguments: { code: "__hold__" } },
            }),
            signal: AbortSignal.timeout(5000),
        });
        await held;
        const stopping = server.stop();
        const response = await pending;
        assert.match(response.headers.get("content-type")!, /^text\/event-stream/);
        const text = await response.text();
        await stopping;
        stopped = true;
        assert.match(text, /"id":7/);
        assert.match(text, /"isError":true/);
        assert.match(text, /shutting down; task outcome may be unknown/);
        assertNoTrackedTasks(server);
    } finally {
        if (!stopped) {
            await server.stop();
        }
        plugin?.terminate();
        restoreEnv();
    }
});
