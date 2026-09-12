import assert from "node:assert/strict";
import test from "node:test";
import { PenpotMcpServer, shouldRegisterDeveloperTools, shouldStartReplServer } from "./PenpotMcpServer";

test("registers developer tools in local devenv mode", () => {
    assert.equal(shouldRegisterDeveloperTools(true, false), true);
});

test("does not register developer tools in multi-user devenv mode", () => {
    assert.equal(shouldRegisterDeveloperTools(true, true), false);
});

test("does not register developer tools when devenv mode is disabled", () => {
    assert.equal(shouldRegisterDeveloperTools(false, false), false);
});

test("starts REPL server in single-user mode when enabled", () => {
    assert.equal(shouldStartReplServer(true, false), true);
});

test("does not start REPL server in multi-user mode even when enabled", () => {
    assert.equal(shouldStartReplServer(true, true), false);
});

test("does not start REPL server when disabled in single-user mode", () => {
    assert.equal(shouldStartReplServer(false, false), false);
});

test("does not start REPL server when disabled in multi-user mode", () => {
    assert.equal(shouldStartReplServer(false, true), false);
});

// ── Pure function tests ────────────────────────────────────────

test("isDevEnvEnabled returns false when PENPOT_MCP_DEVENV is not set", () => {
    assert.equal(PenpotMcpServer.isDevEnvEnabled({}), false);
});

test("isDevEnvEnabled returns false when PENPOT_MCP_DEVENV is 'false'", () => {
    assert.equal(PenpotMcpServer.isDevEnvEnabled({ PENPOT_MCP_DEVENV: "false" }), false);
});

test("isDevEnvEnabled returns true when PENPOT_MCP_DEVENV is 'true'", () => {
    assert.equal(PenpotMcpServer.isDevEnvEnabled({ PENPOT_MCP_DEVENV: "true" }), true);
});

// ── Pure function tests: isReplEnabled ──────────────────────────

test("isReplEnabled returns false when neither env var is set", () => {
    assert.equal(PenpotMcpServer.isReplEnabled({}), false);
});

test("isReplEnabled returns true when PENPOT_MCP_DEVENV is 'true' (fallback)", () => {
    assert.equal(PenpotMcpServer.isReplEnabled({ PENPOT_MCP_DEVENV: "true" }), true);
});

test("isReplEnabled returns true when PENPOT_MCP_REPL_ENABLE is 'true'", () => {
    assert.equal(PenpotMcpServer.isReplEnabled({ PENPOT_MCP_REPL_ENABLE: "true" }), true);
});

test("isReplEnabled returns false when PENPOT_MCP_REPL_ENABLE is 'false' even if DEVENV is true", () => {
    assert.equal(PenpotMcpServer.isReplEnabled({ PENPOT_MCP_REPL_ENABLE: "false", PENPOT_MCP_DEVENV: "true" }), false);
});

test("isReplEnabled returns true when PENPOT_MCP_REPL_ENABLE is 'true' regardless of DEVENV", () => {
    assert.equal(PenpotMcpServer.isReplEnabled({ PENPOT_MCP_REPL_ENABLE: "true" }), true);
});

// ── Integration tests: constructor gating ──────────────────────
//
// Each test uses unique ports to avoid conflicts when tests run
// in the same process. The server is stopped in the finally block
// to release the WebSocket port.

let portCounter = 14_500;
function uniquePorts() {
    const base = portCounter;
    portCounter += 10;
    return { server: base, ws: base + 1, repl: base + 2 };
}

test("constructor does not create ReplServer when PENPOT_MCP_DEVENV is unset", async () => {
    const prev = process.env.PENPOT_MCP_DEVENV;
    const prevPorts = setUniqueEnv();
    delete process.env.PENPOT_MCP_DEVENV;
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), false);
    } finally {
        await server?.stop();
        restoreEnv(prev, prevPorts);
    }
});

test("constructor creates ReplServer when PENPOT_MCP_DEVENV is 'true'", async () => {
    const prev = process.env.PENPOT_MCP_DEVENV;
    const prevPorts = setUniqueEnv();
    process.env.PENPOT_MCP_DEVENV = "true";
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), true);
    } finally {
        await server?.stop();
        restoreEnv(prev, prevPorts);
    }
});

test("constructor creates ReplServer when PENPOT_MCP_REPL_ENABLE is 'true' without DEVENV", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevReplEnable = process.env.PENPOT_MCP_REPL_ENABLE;
    const prevPorts = setUniqueEnv();
    delete process.env.PENPOT_MCP_DEVENV;
    process.env.PENPOT_MCP_REPL_ENABLE = "true";
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), true);
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_REPL_ENABLE", prevReplEnable);
    }
});

test("constructor does not create ReplServer when PENPOT_MCP_REPL_ENABLE is 'false' even with DEVENV", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevReplEnable = process.env.PENPOT_MCP_REPL_ENABLE;
    const prevPorts = setUniqueEnv();
    process.env.PENPOT_MCP_DEVENV = "true";
    process.env.PENPOT_MCP_REPL_ENABLE = "false";
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), false);
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_REPL_ENABLE", prevReplEnable);
    }
});

test("constructor does not create ReplServer in multi-user mode even with DEVENV", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevReplEnable = process.env.PENPOT_MCP_REPL_ENABLE;
    const prevPorts = setUniqueEnv();
    process.env.PENPOT_MCP_DEVENV = "true";
    delete process.env.PENPOT_MCP_REPL_ENABLE;
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(true);
        assert.equal(server.hasReplServer(), false);
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_REPL_ENABLE", prevReplEnable);
    }
});

test("constructor does not create ReplServer in multi-user mode even with explicit REPL_ENABLE", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevReplEnable = process.env.PENPOT_MCP_REPL_ENABLE;
    const prevPorts = setUniqueEnv();
    delete process.env.PENPOT_MCP_DEVENV;
    process.env.PENPOT_MCP_REPL_ENABLE = "true";
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(true);
        assert.equal(server.hasReplServer(), false);
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_REPL_ENABLE", prevReplEnable);
    }
});

test("replHost defaults to localhost and ignores SERVER_HOST", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevServerHost = process.env.PENPOT_MCP_SERVER_HOST;
    const prevReplHost = process.env.PENPOT_MCP_REPL_HOST;
    const prevPorts = setUniqueEnv();
    process.env.PENPOT_MCP_DEVENV = "true";
    process.env.PENPOT_MCP_SERVER_HOST = "0.0.0.0";
    delete process.env.PENPOT_MCP_REPL_HOST;
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), true);
        assert.equal(server.replHost, "localhost");
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_SERVER_HOST", prevServerHost);
        restoreOrDelete("PENPOT_MCP_REPL_HOST", prevReplHost);
    }
});

test("replHost respects PENPOT_MCP_REPL_HOST", async () => {
    const prevDevEnv = process.env.PENPOT_MCP_DEVENV;
    const prevReplHost = process.env.PENPOT_MCP_REPL_HOST;
    const prevPorts = setUniqueEnv();
    process.env.PENPOT_MCP_DEVENV = "true";
    process.env.PENPOT_MCP_REPL_HOST = "0.0.0.0";
    let server: PenpotMcpServer | undefined;
    try {
        server = new PenpotMcpServer(false);
        assert.equal(server.hasReplServer(), true);
        assert.equal(server.replHost, "0.0.0.0");
    } finally {
        await server?.stop();
        restoreEnv(prevDevEnv, prevPorts);
        restoreOrDelete("PENPOT_MCP_REPL_HOST", prevReplHost);
    }
});

// ── Helpers ────────────────────────────────────────────────────

function setUniqueEnv() {
    const ports = uniquePorts();
    const prevServer = process.env.PENPOT_MCP_SERVER_PORT;
    const prevWs = process.env.PENPOT_MCP_WEBSOCKET_PORT;
    const prevRepl = process.env.PENPOT_MCP_REPL_PORT;
    const prevReplHost = process.env.PENPOT_MCP_REPL_HOST;
    process.env.PENPOT_MCP_SERVER_PORT = String(ports.server);
    process.env.PENPOT_MCP_WEBSOCKET_PORT = String(ports.ws);
    process.env.PENPOT_MCP_REPL_PORT = String(ports.repl);
    delete process.env.PENPOT_MCP_REPL_HOST;
    return { prevServer, prevWs, prevRepl, prevReplHost };
}

function restoreEnv(
    devEnv: string | undefined,
    ports: {
        prevServer: string | undefined;
        prevWs: string | undefined;
        prevRepl: string | undefined;
        prevReplHost: string | undefined;
    }
) {
    if (devEnv !== undefined) {
        process.env.PENPOT_MCP_DEVENV = devEnv;
    } else {
        delete process.env.PENPOT_MCP_DEVENV;
    }
    restoreOrDelete("PENPOT_MCP_SERVER_PORT", ports.prevServer);
    restoreOrDelete("PENPOT_MCP_WEBSOCKET_PORT", ports.prevWs);
    restoreOrDelete("PENPOT_MCP_REPL_PORT", ports.prevRepl);
    restoreOrDelete("PENPOT_MCP_REPL_HOST", ports.prevReplHost);
}

function restoreOrDelete(key: string, value: string | undefined) {
    if (value !== undefined) {
        process.env[key] = value;
    } else {
        delete process.env[key];
    }
}
