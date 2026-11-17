import assert from "node:assert/strict";
import { test } from "node:test";
import { RequestGuard } from "./RequestGuard";

test("single-user mode allows only loopback hosts and origins by default", () => {
    const guard = RequestGuard.fromEnvironment({}, false);
    assert.equal(guard.allowsAny(), false);
    assert.equal(guard.check({ host: "localhost:4401" }).isAllowed, true);
    assert.equal(guard.check({ host: "127.0.0.1:4401", origin: "http://localhost:4400" }).isAllowed, true);
    assert.equal(guard.check({ host: "[::1]:4401" }).isAllowed, true);
    assert.equal(guard.check({ host: "attacker.example:4401" }).isAllowed, false);
    assert.equal(guard.check({ host: "localhost:4401", origin: "https://attacker.example" }).isAllowed, false);
    assert.equal(guard.check({ host: "localhost:4401", origin: "null" }).isAllowed, false);
    assert.equal(guard.check({}).isAllowed, false);
});

test("multi-user mode allows any host and origin by default", () => {
    const guard = RequestGuard.fromEnvironment({ PENPOT_MCP_ALLOWED_HOSTS: " , " }, true);
    assert.equal(guard.allowsAny(), true);
    assert.equal(guard.check({ host: "penpot.example.com", origin: "https://other.example" }).isAllowed, true);
    assert.equal(guard.describe(), "<any>");
});

test("the configured hosts replace the default in either mode", () => {
    for (const isMultiUserMode of [false, true]) {
        const guard = RequestGuard.fromEnvironment(
            { PENPOT_MCP_ALLOWED_HOSTS: " Penpot.Example.com, localhost " },
            isMultiUserMode
        );
        assert.equal(guard.describe(), "penpot.example.com, localhost");
        assert.equal(guard.check({ host: "penpot.example.com", origin: "https://penpot.example.com" }).isAllowed, true);
        assert.equal(guard.check({ host: "localhost:4401" }).isAllowed, true);
        assert.equal(guard.check({ host: "127.0.0.1:4401" }).isAllowed, false);
        assert.equal(guard.check({ host: "penpot.example.com", origin: "https://attacker.example" }).isAllowed, false);
    }
});

test("the wildcard turns the check off in single-user mode", () => {
    const guard = RequestGuard.fromEnvironment({ PENPOT_MCP_ALLOWED_HOSTS: "localhost, *" }, false);
    assert.equal(guard.describe(), "<any>");
    assert.equal(guard.check({ host: "attacker.example", origin: "https://attacker.example" }).isAllowed, true);
});

test("hostnames, bracketed IPv6 addresses and the wildcard are valid entries", () => {
    const guard = RequestGuard.fromEnvironment(
        { PENPOT_MCP_ALLOWED_HOSTS: "localhost, 127.0.0.1, [::1], penpot-mcp, penpot_mcp, penpot.example.com" },
        false
    );
    assert.equal(guard.check({ host: "penpot-mcp:4402" }).isAllowed, true);
    assert.equal(guard.check({ host: "[::1]:4401" }).isAllowed, true);
});

test("entries with a scheme, port or path stop the server from starting", () => {
    for (const entry of ["http://localhost", "localhost:4401", "localhost/", "[::1]:4401", "local host"]) {
        assert.throws(
            () => RequestGuard.fromEnvironment({ PENPOT_MCP_ALLOWED_HOSTS: `localhost, ${entry}` }, false),
            (error: Error) => error.message.includes(`"${entry}"`)
        );
    }
});
