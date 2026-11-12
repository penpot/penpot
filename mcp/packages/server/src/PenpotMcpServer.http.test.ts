import assert from "node:assert/strict";
import { afterEach, beforeEach, test } from "node:test";
import { type CallToolResult, Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
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
    delete process.env.PENPOT_MCP_AUTH_URI;
    server = new PenpotMcpServer(true);
    baseUrl = `http://127.0.0.1:${server.port}`;
    await server.start();
});

afterEach(async () => {
    await server?.stop();
    process.env = previousEnv;
});

async function modernRequest(method: string, params: Record<string, unknown> = {}, query = "", bearer?: string) {
    return fetch(`${baseUrl}/mcp${query}`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Accept: "application/json, text/event-stream",
            "MCP-Protocol-Version": "2026-07-28",
            "Mcp-Method": method,
            ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
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
                token === "alice" ? "" : `?userToken=${token}`,
                token === "alice" ? token : undefined
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

test("passes an explicit Penpot session ID from the tool call to plugin dispatch", async (t) => {
    t.mock.method(server.pluginBridge, "executePluginTask", async (_task: unknown, sessionId?: string) => ({
        data: sessionId,
    }));
    const response = await modernRequest(
        "tools/call",
        {
            name: "execute_code",
            arguments: { code: "return penpot.currentFile.id;", sessionId: "chosen-tab" },
        },
        "?userToken=alice"
    );
    assert.equal(response.status, 200);
    const body = await response.json();
    assert.match(JSON.stringify(body), /chosen-tab/);
});

test("accepts tool calls with request bodies larger than 100 KiB", async (t) => {
    t.mock.method(server.pluginBridge, "executePluginTask", async () => ({ data: "ok" }));
    const code = `return "${"x".repeat(200_000)}".length;`;
    const response = await modernRequest("tools/call", { name: "execute_code", arguments: { code } });
    assert.equal(response.status, 200);
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

test("OAuth challenges missing credentials and accepts bearer and query tokens", async () => {
    await server.stop();
    process.env.PENPOT_MCP_AUTH_URI = "https://penpot.example/api/mcp/oauth";
    server = new PenpotMcpServer(true);
    await server.start();

    const missing = await modernRequest("tools/list");
    assert.equal(missing.status, 401);
    assert.match(missing.headers.get("www-authenticate") ?? "", /resource_metadata=/);

    for (const useBearer of [true, false]) {
        const client = new Client({ name: "oauth-test", version: "1" });
        const transport = new StreamableHTTPClientTransport(
            new URL(`${baseUrl}/mcp${useBearer ? "" : "?userToken=alice"}`),
            useBearer ? { requestInit: { headers: { Authorization: "Bearer alice" } } } : undefined
        );
        try {
            await client.connect(transport);
            const result = await client.listTools();
            const tool = result.tools.find((tool) => tool.name === "execute_code");
            assert.ok(tool, "authenticated clients can discover execute_code");
        } finally {
            await client.close();
        }
    }
});

/**
 * Makes the plugin bridge answer each dispatched task at once with the given responses, in order.
 */
function answerTasks(t: { mock: any }, responses: Array<Record<string, unknown>>): void {
    const bridge = server.pluginBridge as any;
    t.mock.method(bridge.dispatcher, "dispatch", async (task: { id: string }) => {
        bridge.pendingTasks.set(task.id, task);
        bridge.handlePluginTaskResponse({ id: task.id, ...responses.shift() });
    });
}

async function callExecuteCode(): Promise<CallToolResult> {
    const response = await modernRequest(
        "tools/call",
        { name: "execute_code", arguments: { code: "return 1;" } },
        "?userToken=alice"
    );
    const body = (await response.json()) as { result: CallToolResult };
    assert.equal(response.status, 200, JSON.stringify(body));
    return body.result;
}

const rollback = { fileIds: ["file-1"], pageIds: ["page-1"], shapeIds: ["shape-1", "shape-2"] };

test("puts a warning first in the tool response that reports a rollback", async (t) => {
    answerTasks(t, [
        { success: true, data: { result: 1, log: "" }, rollbacks: [rollback] },
        { success: true, data: { result: 2, log: "" } },
    ]);

    const first = await callExecuteCode();
    const second = await callExecuteCode();

    assert.equal(first.content.length, 2);
    const [notice, data] = first.content;
    assert.equal(notice.type, "text");
    assert.match(notice.text, /CHANGES WERE ROLLED BACK/);
    assert.match(notice.text, /Pages: page-1/);
    assert.match(notice.text, /Shapes: shape-1, shape-2/);
    assert.equal(data.type, "text");
    assert.equal(JSON.parse(data.text).result, 1);

    assert.equal(second.content.length, 1);
});

test("puts a rollback warning first in a failed tool response too", async (t) => {
    answerTasks(t, [{ success: false, error: "Shape not found", rollbacks: [rollback] }]);

    const result = await callExecuteCode();

    assert.equal(result.content.length, 2);
    const [notice, failure] = result.content;
    assert.equal(notice.type, "text");
    assert.match(notice.text, /CHANGES WERE ROLLED BACK/);
    assert.equal(failure.type, "text");
    assert.match(failure.text, /Tool execution failed: .*Shape not found/);
});

/**
 * A plugin connection whose socket keeps the messages sent to it.
 */
function fakeConnection(): { socket: { readyState: number; send: (message: string) => void }; sent: unknown[] } {
    const sent: unknown[] = [];
    return { socket: { readyState: 1, send: (message: string) => sent.push(JSON.parse(message)) }, sent };
}

test("gives the rollbacks of a response that came after its task timed out back to the plugin", () => {
    const bridge = server.pluginBridge as any;
    const connection = fakeConnection();

    bridge.handlePluginTaskResponse(
        { id: "timed-out", success: true, data: { result: 1, log: "" }, rollbacks: [rollback] },
        connection
    );

    assert.deepEqual(connection.sent, [{ type: "unclaimed-rollbacks", rollbacks: [rollback] }]);
});

test("sends nothing back for a late response without rollbacks", () => {
    const bridge = server.pluginBridge as any;
    const connection = fakeConnection();

    bridge.handlePluginTaskResponse({ id: "timed-out", success: true, data: { result: 1, log: "" } }, connection);

    assert.deepEqual(connection.sent, []);
});
