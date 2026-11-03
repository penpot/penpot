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
let requests: { url: string; authorization?: string; client?: string; body: unknown }[];
let responseStatus: number;
let nextPort = 17_700;

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
    backend = createServer(async (req, res) => {
        let body = "";
        for await (const chunk of req) body += chunk;
        requests.push({
            url: req.url!,
            authorization: req.headers.authorization,
            client: req.headers["x-client"] as string,
            body: JSON.parse(body),
        });
        res.writeHead(responseStatus, {
            "content-type": "application/json",
            ...(responseStatus === 307 ? { location: `${backendUri}/redirected` } : {}),
        });
        res.end(JSON.stringify({ status: "queued", detail: "private backend diagnostics" }));
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
    client = new Client({ name: "feedback-test", version: "1" });
    const url = new URL(`http://127.0.0.1:${server.port}/mcp`);
    if (token) url.searchParams.set("userToken", token);
    await client.connect(new StreamableHTTPClientTransport(url));
    return client;
}

const feedback = { subject: "Export fails", feedback: "Expected a PNG, received an error." };

for (const multiUser of [false, true]) {
    test(`feedback is absent without a backend URI (multi-user=${multiUser})`, async () => {
        const connected = await connect(multiUser, "alice");
        assert.ok(!(await connected.listTools()).tools.some((tool) => tool.name === "submit_feedback"));
    });

    test(`feedback works without a plugin (multi-user=${multiUser})`, async () => {
        process.env.PENPOT_MCP_BACKEND_URI = backendUri;
        const connected = await connect(multiUser, "alice");
        const tool = (await connected.listTools()).tools.find((tool) => tool.name === "submit_feedback");
        assert.ok(tool);
        assert.deepEqual(tool.inputSchema.required, ["subject", "feedback"]);
        const result = await connected.callTool({ name: "submit_feedback", arguments: feedback });
        assert.deepEqual(result.content, [
            { type: "text", text: "Feedback queued. It is emailed in the background, so delivery is not confirmed." },
        ]);
        assert.deepEqual(requests, [
            {
                url: "/penpot/api/main/methods/send-mcp-feedback",
                authorization: "Token alice",
                client: "penpot-mcp",
                body: feedback,
            },
        ]);
    });

    test(`missing credentials leave the tool listed but prevent submission (multi-user=${multiUser})`, async () => {
        process.env.PENPOT_MCP_BACKEND_URI = backendUri;
        const connected = await connect(multiUser);
        assert.ok((await connected.listTools()).tools.some((tool) => tool.name === "submit_feedback"));
        const result = await connected.callTool({ name: "submit_feedback", arguments: feedback });
        assert.match(JSON.stringify(result.content), /userToken.*MCP.*URL/);
        assert.equal(requests.length, 0);
    });
}

test("blank backend URI does not enable feedback", async () => {
    process.env.PENPOT_MCP_BACKEND_URI = "  ";
    const connected = await connect(false);
    assert.ok(!(await connected.listTools()).tools.some((tool) => tool.name === "submit_feedback"));
});

test("rejects invalid input without contacting the backend", async () => {
    process.env.PENPOT_MCP_BACKEND_URI = backendUri;
    const connected = await connect(false, "alice");
    for (const args of [
        { ...feedback, subject: "" },
        { ...feedback, subject: " " },
        { ...feedback, subject: "a\r\nBcc: other@example.com" },
        { ...feedback, subject: "a\tb" },
        { ...feedback, subject: "a\u0085b" },
        { ...feedback, feedback: "a\u0000b" },
        { ...feedback, feedback: "a\u001bb" },
        { ...feedback, subject: "x".repeat(121) },
        { ...feedback, feedback: " \n" },
        { ...feedback, feedback: "x".repeat(5001) },
    ]) {
        // SDK versions may return a tool error or reject with a protocol error.
        try {
            const result = await connected.callTool({ name: "submit_feedback", arguments: args });
            assert.equal(result.isError, true);
        } catch (error) {
            assert.match(String(error), /invalid|validation/i);
        }
    }
    assert.equal(requests.length, 0);
});

test("preserves deployment prefixes with and without a trailing slash", async () => {
    for (const uri of [backendUri, backendUri + "/"]) {
        await new PenpotBackendClient(uri).submitFeedback("alice", feedback);
    }
    assert.deepEqual(
        requests.map((request) => request.url),
        ["/penpot/api/main/methods/send-mcp-feedback", "/penpot/api/main/methods/send-mcp-feedback"]
    );
});

test("keeps credentials isolated across concurrent submissions", async () => {
    const backendClient = new PenpotBackendClient(backendUri);
    await Promise.all(
        ["alice", "bob"].map((token) =>
            backendClient.submitFeedback(token, {
                subject: token,
                feedback: "A concrete problem.",
            })
        )
    );
    assert.deepEqual(
        requests
            .map((request) => ({
                authorization: request.authorization,
                subject: (request.body as typeof feedback).subject,
            }))
            .sort((a, b) => a.subject.localeCompare(b.subject)),
        [
            { authorization: "Token alice", subject: "alice" },
            { authorization: "Token bob", subject: "bob" },
        ]
    );
});

for (const status of [400, 401, 403, 404, 429, 503, 500, 307]) {
    test(`does not retry or expose response bodies on status ${status}`, async () => {
        responseStatus = status;
        const backendClient = new PenpotBackendClient(backendUri);
        await assert.rejects(backendClient.submitFeedback("secret-token", feedback), (error: Error) => {
            assert.doesNotMatch(error.message, /private backend diagnostics|secret-token/);
            return true;
        });
        assert.equal(requests.length, 1);
    });
}

test("bounds network waits and does not retry an uncertain submission", async (t) => {
    backend.removeAllListeners("request");
    let count = 0;
    backend.on("request", (_req, _res) => {
        count++;
    });
    const timeout = AbortSignal.timeout.bind(AbortSignal);
    t.mock.method(AbortSignal, "timeout", (milliseconds: number) => {
        assert.equal(milliseconds, 10_000);
        return timeout(50);
    });
    await assert.rejects(
        new PenpotBackendClient(backendUri).submitFeedback("secret-token", feedback),
        /could not be confirmed.*Do not retry automatically/
    );
    assert.ok(count <= 1);
});

test("rejects invalid backend configuration without echoing credentials", () => {
    for (const uri of [
        "invalid",
        "file:///tmp",
        "https://user:secret@example.com",
        "https://example.com/?secret=value",
        "https://example.com/#fragment",
    ]) {
        assert.throws(
            () => new PenpotBackendClient(uri),
            (error: Error) => {
                assert.doesNotMatch(error.message, /secret|value|user:/);
                return true;
            }
        );
    }
});
