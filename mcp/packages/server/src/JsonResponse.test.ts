import assert from "node:assert/strict";
import test from "node:test";
import { once } from "node:events";
import { WebSocket } from "ws";
import { PenpotMcpServer } from "./PenpotMcpServer";

// Exercise the actual HTTP endpoint and plugin bridge, not a transport test double.
test("opt-in JSON replies preserve request IDs, errors and user isolation across 200 calls", async () => {
    const names = [
        "PENPOT_MCP_SERVER_HOST",
        "PENPOT_MCP_SERVER_PORT",
        "PENPOT_MCP_WEBSOCKET_PORT",
        "PENPOT_MCP_JSON_RESPONSE",
        "PENPOT_MCP_REDIS_URI",
    ];
    const previous = names.map((name) => process.env[name]);
    Object.assign(process.env, {
        PENPOT_MCP_SERVER_HOST: "127.0.0.1",
        PENPOT_MCP_SERVER_PORT: "0",
        PENPOT_MCP_WEBSOCKET_PORT: "0",
        PENPOT_MCP_JSON_RESPONSE: "true",
    });
    delete process.env.PENPOT_MCP_REDIS_URI;
    const server = new PenpotMcpServer(true);
    let plugin: WebSocket | undefined;
    try {
        await server.start();
        const internals = server as any;
        // Express retains the server via app.listen; capture it for bounded cleanup below.
        const http = internals.httpServer;
        assert.ok(http, "HTTP server must be retained for graceful shutdown");
        const url = `http://127.0.0.1:${http.address().port}/mcp?userToken=fixture-owner`;
        const wsServer = (server.pluginBridge as any).wsServer;
        if (!wsServer.address()) await once(wsServer, "listening");
        plugin = new WebSocket(`ws://127.0.0.1:${wsServer.address().port}/?userToken=fixture-owner`);
        plugin.on("message", (raw) => {
            const task = JSON.parse(raw.toString());
            plugin!.send(JSON.stringify({ id: task.id, success: true, data: { result: task.params.code } }));
        });
        await once(plugin, "open");
        const headers: Record<string, string> = {
            "Content-Type": "application/json",
            Accept: "application/json, text/event-stream",
        };
        const post = (body: object, target = url) =>
            fetch(target, { method: "POST", headers, body: JSON.stringify(body), signal: AbortSignal.timeout(5000) });
        const init = await post({
            jsonrpc: "2.0",
            id: 1,
            method: "initialize",
            params: {
                protocolVersion: "2025-03-26",
                capabilities: {},
                clientInfo: { name: "regression", version: "1" },
            },
        });
        assert.match(init.headers.get("content-type")!, /^application\/json/);
        assert.equal(((await init.json()) as any).id, 1);
        headers["mcp-session-id"] = init.headers.get("mcp-session-id")!;
        assert.ok(headers["mcp-session-id"]);
        assert.equal((await post({ jsonrpc: "2.0", method: "notifications/initialized" })).status, 202);
        for (let id = 2; id < 202; id++) {
            const response = await post({
                jsonrpc: "2.0",
                id,
                method: "tools/call",
                params: { name: "execute_code", arguments: { code: `return ${id};` } },
            });
            assert.match(response.headers.get("content-type")!, /^application\/json/);
            const reply: any = await response.json();
            assert.equal(reply.id, id);
            assert.ok(reply.result.content.length);
        }
        // A fresh unauthenticated session must not access the authenticated plugin.
        delete headers["mcp-session-id"];
        const anonymous = url.split("?")[0];
        const anonInit = await post(
            {
                jsonrpc: "2.0",
                id: 300,
                method: "initialize",
                params: {
                    protocolVersion: "2025-03-26",
                    capabilities: {},
                    clientInfo: { name: "negative", version: "1" },
                },
            },
            anonymous
        );
        headers["mcp-session-id"] = anonInit.headers.get("mcp-session-id")!;
        await anonInit.json();
        const denied = await post(
            {
                jsonrpc: "2.0",
                id: 301,
                method: "tools/call",
                params: { name: "execute_code", arguments: { code: "return 1;" } },
            },
            anonymous
        );
        const denial: any = await denied.json();
        assert.equal(denial.id, 301);
        assert.match(JSON.stringify(denial), /authentication|userToken/);
    } finally {
        if (plugin) {
            plugin.terminate();
            await once(plugin, "close");
        }
        await server.stop();
        names.forEach((name, index) =>
            previous[index] === undefined ? delete process.env[name] : (process.env[name] = previous[index])
        );
    }
});
