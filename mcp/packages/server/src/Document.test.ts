import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { once } from "node:events";
import { afterEach, beforeEach, test } from "node:test";
import { Client, StreamableHTTPClientTransport } from "@modelcontextprotocol/client";
import { PenpotMcpServer } from "./PenpotMcpServer";
import { PenpotBackendClient } from "./PenpotBackendClient";

let previousEnv: NodeJS.ProcessEnv;
let backend: Server;
let backendUri: string;
let server: PenpotMcpServer | undefined;
let client: Client | undefined;
let requests: { authorization?: string; body: unknown; url?: string }[];
let responseStatus: number;
let responseBody: object;
let nextPort = 18_700;

test("rejects oversized document responses", async () => {
    responseBody = { items: ["x".repeat(2_000_001)] };
    await assert.rejects(new PenpotBackendClient(backendUri).getDocument("alice", { resource: "teams" }), /too large/);
});

test("reports changed documents without exposing backend diagnostics", async () => {
    responseStatus = 409;
    responseBody = { hint: "private backend detail" };
    await assert.rejects(
        new PenpotBackendClient(backendUri).getDocument("alice", { resource: "teams" }),
        /changed.*restart/i
    );
});

test("does not expose invalid JSON response content", async () => {
    backend.removeAllListeners("request");
    backend.on("request", (_req, res) => res.end("private backend detail"));
    await assert.rejects(
        new PenpotBackendClient(backendUri).getDocument("alice", { resource: "teams" }),
        (error: Error) => {
            assert.doesNotMatch(error.message, /private backend detail/);
            return true;
        }
    );
});

test("requires credentials before reading", async () => {
    process.env.PENPOT_MCP_BACKEND_URI = backendUri;
    const connected = await connect(false);
    const result = await connected.callTool({ name: "get_document", arguments: { resource: "teams" } });
    assert.match(JSON.stringify(result.content), /Authenticate/);
    assert.equal(requests.length, 0);
});

test("does not advertise backend reads without configuration", async () => {
    const connected = await connect(false);
    assert.ok(!(await connected.listTools()).tools.some((tool) => tool.name === "get_document"));
});

beforeEach(async () => {
    previousEnv = { ...process.env };
    process.env.PENPOT_MCP_SERVER_HOST = "127.0.0.1";
    process.env.PENPOT_MCP_SERVER_PORT = String(nextPort++);
    process.env.PENPOT_MCP_WEBSOCKET_PORT = "0";
    process.env.PENPOT_MCP_DEVENV = "false";
    process.env.PENPOT_MCP_REPL_ENABLE = "false";
    delete process.env.PENPOT_MCP_REDIS_URI;
    delete process.env.PENPOT_MCP_BACKEND_URI;
    requests = [];
    responseStatus = 200;
    responseBody = { items: [{ id: "team-id", name: "Design" }], nextOffset: null };
    backend = createServer(async (req, res) => {
        let body = "";
        for await (const chunk of req) {
            body += chunk;
        }
        requests.push({ authorization: req.headers.authorization, body: JSON.parse(body), url: req.url });
        res.writeHead(responseStatus, { "content-type": "application/json" });
        res.end(JSON.stringify(responseBody));
    });
    backend.listen(0, "127.0.0.1");
    await once(backend, "listening");
    const address = backend.address();
    assert.ok(address && typeof address === "object");
    backendUri = `http://127.0.0.1:${address.port}/penpot`;
});

afterEach(async () => {
    await client?.close();
    await server?.stop();
    client = undefined;
    server = undefined;
    backend.closeAllConnections();
    await new Promise<void>((resolve, reject) => backend.close((error) => (error ? reject(error) : resolve())));
    process.env = previousEnv;
});

async function connect(multiUser: boolean, token?: string) {
    server = new PenpotMcpServer(multiUser);
    await server.start();
    client = new Client({ name: "document-test", version: "1" });
    const url = new URL(`http://127.0.0.1:${server.port}/mcp`);
    if (token) {
        url.searchParams.set("userToken", token);
    }
    await client.connect(new StreamableHTTPClientTransport(url));
    return client;
}

test("isolates overlapping users' document credentials and results", { timeout: 15_000 }, async () => {
    process.env.PENPOT_MCP_BACKEND_URI = backendUri;
    const alice = await connect(true, "alice");
    const bob = new Client({ name: "document-test-bob", version: "1" });
    const url = new URL(`http://127.0.0.1:${server!.port}/mcp`);
    url.searchParams.set("userToken", "bob");
    await bob.connect(new StreamableHTTPClientTransport(url));

    const pending: { authorization?: string; offset: number; respond: () => void }[] = [];
    backend.removeAllListeners("request");
    backend.on("request", async (req, res) => {
        let body = "";
        for await (const chunk of req) {
            body += chunk;
        }
        const { offset } = JSON.parse(body);
        const authorization = req.headers.authorization;
        pending.push({
            authorization,
            offset,
            respond: () => {
                res.writeHead(200, { "content-type": "application/json" });
                res.end(JSON.stringify({ items: [{ name: `${authorization}:${offset}` }], nextOffset: null }));
            },
        });
        // Neither request can finish until both users reach the backend.
        if (pending.length === 2) {
            pending[1].respond();
            pending[0].respond();
        }
    });
    try {
        const [aliceResult, bobResult] = await Promise.all([
            alice.callTool({ name: "get_document", arguments: { resource: "teams", offset: 1 } }),
            bob.callTool({ name: "get_document", arguments: { resource: "teams", offset: 2 } }),
        ]);
        assert.deepEqual(
            pending.map(({ authorization, offset }) => ({ authorization, offset })).sort((a, b) => a.offset - b.offset),
            [
                { authorization: "Token alice", offset: 1 },
                { authorization: "Token bob", offset: 2 },
            ]
        );
        for (const [result, name] of [
            [aliceResult, "Token alice:1"],
            [bobResult, "Token bob:2"],
        ] as const) {
            assert.deepEqual(result.content, [
                { type: "text", text: JSON.stringify({ items: [{ name }], nextOffset: null }) },
            ]);
        }
    } finally {
        await bob.close();
    }
});

for (const multiUser of [false, true]) {
    test(`reads backend documents without a plugin (multi-user=${multiUser})`, async () => {
        process.env.PENPOT_MCP_BACKEND_URI = backendUri;
        const connected = await connect(multiUser, "alice");
        assert.ok((await connected.listTools()).tools.some((tool) => tool.name === "get_document"));
        const result = await connected.callTool({ name: "get_document", arguments: { resource: "teams" } });
        assert.deepEqual(result.content, [{ type: "text", text: JSON.stringify(responseBody) }]);
        assert.deepEqual(requests, [
            {
                authorization: "Token alice",
                body: { resource: "teams" },
                url: "/penpot/api/main/methods/get-mcp-document",
            },
        ]);
    });
}
