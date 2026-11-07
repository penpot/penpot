import assert from "node:assert/strict";
import { test } from "node:test";
import express from "express";
import { McpAuthentication } from "./McpAuthentication";

test("OAuth is opt-in and requires multi-user mode", () => {
    assert.equal(new McpAuthentication(undefined, false).isOAuthEnabled, false);
    assert.equal(new McpAuthentication("", true).isOAuthEnabled, false);
    assert.throws(() => new McpAuthentication("https://penpot.example/api/mcp/oauth", false), /multi-user/);
    for (const uri of [
        "not a URL",
        "http://remote.example/auth",
        "https://user:secret@example.com/auth",
        "https://example.com/auth?x=1",
        "https://example.com/auth#fragment",
    ]) {
        assert.throws(() => new McpAuthentication(uri, true), /PENPOT_MCP_AUTH_URI/);
    }
    assert.equal(new McpAuthentication("http://localhost:3449/api/mcp/oauth", true).isOAuthEnabled, true);
});

async function withEndpoint(uri: string | undefined, run: (base: string) => Promise<void>) {
    const auth = new McpAuthentication(uri, true);
    const app = express();
    app.all("/mcp", (req, res) => {
        const result = auth.authenticate(req, res);
        if (!result.isRejected) res.json({ token: result.userToken ?? null });
    });
    const server = app.listen(0, "127.0.0.1");
    await new Promise<void>((resolve) => server.once("listening", resolve));
    const address = server.address();
    assert.ok(address && typeof address !== "string");
    try {
        await run(`http://127.0.0.1:${address.port}/mcp`);
    } finally {
        await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
}

test("missing credentials challenge the client only when OAuth is enabled", async () => {
    await withEndpoint(undefined, async (base) => {
        const response = await fetch(base);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), { token: null });
    });
    await withEndpoint("https://penpot.example/api/mcp/oauth/", async (base) => {
        const response = await fetch(base);
        assert.equal(response.status, 401);
        assert.equal(
            response.headers.get("www-authenticate"),
            'Bearer resource_metadata="https://penpot.example/api/mcp/oauth/resource", scope="mcp"'
        );
        assert.equal(response.headers.get("cache-control"), "no-store");
    });
});

test("bearer credentials take precedence and query credentials remain supported", async () => {
    for (const uri of [undefined, "https://penpot.example/api/mcp/oauth"]) {
        await withEndpoint(uri, async (base) => {
            const bearer = await fetch(base + "?userToken=query-token", {
                headers: { Authorization: "bEaReR header-token" },
            });
            assert.deepEqual(await bearer.json(), { token: "header-token" });
            const query = await fetch(base + "?userToken=query-token");
            assert.deepEqual(await query.json(), { token: "query-token" });
        });
    }
});

test("malformed credentials never fall back to another token", async () => {
    await withEndpoint("https://penpot.example/api/mcp/oauth", async (base) => {
        for (const header of ["Basic abc", "Bearer", "Bearer two tokens", "Bearer a, Bearer b"]) {
            const response = await fetch(base + "?userToken=valid", { headers: { Authorization: header } });
            assert.equal(response.status, 401, header);
            assert.deepEqual(await response.json(), { error: "invalid_token" });
        }
        for (const query of ["?userToken=a&userToken=b", "?userToken=", "?userToken=a%20b"]) {
            assert.equal((await fetch(base + query)).status, 401, query);
        }
    });
});
