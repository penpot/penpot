import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import {
    Client,
    SSEClientTransport,
    StreamableHTTPClientTransport,
    type CallToolResult,
    type ListToolsResult,
} from "@modelcontextprotocol/client";
import { PenpotMcpServer } from "./PenpotMcpServer";

let server: PenpotMcpServer;
let baseUrl: string;
let previousEnv: NodeJS.ProcessEnv;
let nextPort = 16_500;

beforeEach(async () => {
    previousEnv = { ...process.env };
    process.env.PENPOT_MCP_SERVER_HOST = "127.0.0.1";
    process.env.PENPOT_MCP_SERVER_PORT = String(nextPort++);
    process.env.PENPOT_MCP_WEBSOCKET_PORT = "0";
    process.env.PENPOT_MCP_DEVENV = "false";
    process.env.PENPOT_MCP_REPL_ENABLE = "false";
    delete process.env.PENPOT_MCP_REDIS_URI;
    server = new PenpotMcpServer(true);
    baseUrl = `http://127.0.0.1:${server.port}`;
    await server.start();
});

afterEach(async () => {
    await server?.stop();
    process.env = previousEnv;
});

async function modernRequest(method: string, params: Record<string, unknown> = {}, query = "") {
    return fetch(`${baseUrl}/mcp${query}`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Accept: "application/json, text/event-stream",
            "Mcp-Method": method,
            ...(typeof params.name === "string" ? { "Mcp-Name": params.name } : {}),
        },
        body: JSON.stringify({
            jsonrpc: "2.0",
            id: 1,
            method,
            params: {
                ...params,
                _meta: {
                    "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                    "io.modelcontextprotocol/clientInfo": { name: "penpot-test", version: "1" },
                    "io.modelcontextprotocol/clientCapabilities": {},
                },
            },
        }),
    });
}

test("serves repeated modern client requests without allocating a session", async () => {
    const client = new Client(
        { name: "modern-test", version: "1" },
        { versionNegotiation: { mode: { pin: "2026-07-28" } } }
    );
    const transport = new StreamableHTTPClientTransport(new URL(`${baseUrl}/mcp`));
    try {
        await client.connect(transport);
        for (let i = 0; i < 2; i++) {
            const result = await client.listTools();
            assert.equal(transport.sessionId, undefined);
            const tool = result.tools.find((tool) => tool.name === "execute_code");
            assert.ok(tool);
            assert.equal(tool.inputSchema.type, "object");
            assert.deepEqual(tool.inputSchema.required, ["code"]);
        }
    } finally {
        await client.close();
    }
});

test("isolates user tokens across overlapping tool calls with the same request ID", async (t) => {
    let release!: () => void;
    const bothStarted = new Promise<void>((resolve) => {
        release = resolve;
    });
    let started = 0;
    t.mock.method(server.pluginBridge, "executePluginTask", async () => {
        if (++started === 2) release();
        await bothStarted;
        return { data: server.getSessionContext()?.userToken ?? null };
    });

    const results = await Promise.all(
        ["alice", "bob"].map(async (token) => {
            const response = await modernRequest(
                "tools/call",
                {
                    name: "execute_code",
                    arguments: { code: "return 1;" },
                },
                `?userToken=${token}`
            );
            const body = (await response.json()) as { result: CallToolResult };
            assert.equal(response.status, 200, JSON.stringify(body));
            const content = body.result.content[0];
            assert.equal(content.type, "text");
            return JSON.parse(content.text);
        })
    );
    assert.deepEqual(results, ["alice", "bob"]);
    assert.equal(server.getSessionContext(), undefined);
});

test("supports older Streamable HTTP clients without allocating a session", async () => {
    const client = new Client({ name: "legacy-test", version: "1" });
    const transport = new StreamableHTTPClientTransport(new URL(`${baseUrl}/mcp`));
    try {
        await client.connect(transport);
        assert.equal(transport.sessionId, undefined);
        const result = await client.listTools();
        assert.ok(result.tools.some((tool) => tool.name === "execute_code"));
    } finally {
        await client.close();
    }
});

test("preserves legacy SSE tool calls and their connection token", async (t) => {
    t.mock.method(server.pluginBridge, "executePluginTask", async () => ({
        data: server.getSessionContext()?.userToken,
    }));
    const client = new Client({ name: "sse-test", version: "1" });
    try {
        await client.connect(new SSEClientTransport(new URL(`${baseUrl}/sse?userToken=alice`)));
        const result = await client.callTool({ name: "execute_code", arguments: { code: "return 1;" } });
        assert.equal(result.content[0].type, "text");
        assert.equal(JSON.parse(result.content[0].text as string), "alice");
    } finally {
        await client.close();
    }
});
