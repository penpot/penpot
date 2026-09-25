import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";
import { shouldReconnectAfterClose } from "./ReconnectPolicy.ts";
import { SessionId } from "./SessionId.ts";
import { SessionIdDisplay } from "./SessionIdDisplay.ts";

/** Compiles the actual plugin entry point for a simulated browser boundary. */
function compile(name: string) {
    return ts.transpileModule(readFileSync(new URL(name, import.meta.url), "utf8"), {
        compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText;
}

function pluginUi() {
    const messages: any[] = [];
    const sockets: FakeSocket[] = [];
    const events = new Map<string, (event?: any) => void>();
    const timers = new Map<number, () => void>();
    let timerId = 0;
    class FakeSocket {
        static OPEN = 1;
        static CONNECTING = 0;
        readyState = 0;
        sent: any[] = [];
        onopen?: () => void;
        onclose?: (event: any) => void;
        onmessage?: (event: any) => void;
        constructor(_url: string) {
            sockets.push(this);
        }
        open() {
            this.readyState = 1;
            this.onopen?.();
        }
        send(raw: string) {
            this.sent.push(JSON.parse(raw));
        }
        close() {
            this.readyState = 3;
            this.onclose?.({ code: 1000 });
        }
        receive(message: any) {
            this.onmessage?.({ data: JSON.stringify(message) });
        }
    }
    const addEventListener = (name: string, handler: (event?: any) => void) => events.set(name, handler);
    runInNewContext(compile("main.ts"), {
        exports: {},
        require: (name: string) => {
            if (name === "./style.css") return {};
            if (name === "./ReconnectPolicy") return { shouldReconnectAfterClose };
            if (name === "./SessionId") return { SessionId };
            if (name === "./SessionIdDisplay") return { SessionIdDisplay };
            throw new Error(`Unexpected import: ${name}`);
        },
        console: { log() {}, error() {}, warn() {} },
        crypto: { randomUUID },
        URLSearchParams,
        WebSocket: FakeSocket,
        window: { location: { hash: "" }, addEventListener },
        document: { body: { dataset: {} }, getElementById: () => null, addEventListener },
        parent: { postMessage: (message: any) => messages.push(JSON.parse(JSON.stringify(message))) },
        setTimeout: (callback: () => void) => {
            timers.set(++timerId, callback);
            return timerId;
        },
        clearTimeout: (id: number) => timers.delete(id),
        setInterval: () => ++timerId,
        clearInterval: () => {},
        PENPOT_MCP_WEBSOCKET_URL: "ws://localhost:4402",
    });
    const message = (data: any) => events.get("message")!({ data });
    const connect = () => {
        message({ type: "start-server" });
        sockets.at(-1)!.open();
        return messages.filter((message) => message.type === "connection-metadata-request").at(-1)!.sessionId;
    };
    return { messages, sockets, events, timers, message, connect };
}

test("sends file metadata before marking a plugin connection ready", async () => {
    const ui = pluginUi();
    const sessionId = ui.connect();
    const initialization = {
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId, fileId: "file-1", fileName: "Design" },
    };
    await ui.message(initialization);
    const registeredSession = ui.sockets[0].sent[0].session;
    assert.equal(registeredSession.sessionId, "pq3gxqddgj");
    assert.notEqual(registeredSession.sessionId, sessionId);
    assert.deepEqual(registeredSession, { ...initialization.session, sessionId: registeredSession.sessionId });
    assert.equal(
        ui.messages.some((message) => message.status === "connected"),
        false
    );
    ui.sockets[0].receive({ type: "initialized" });
    assert.equal(ui.messages.at(-1).status, "connected");
});

test("reconnects with the same session ID and ignores metadata for the old socket", async () => {
    const ui = pluginUi();
    const firstId = ui.connect();
    await ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: firstId, fileId: "file-1", fileName: "Design" },
    });
    const sessionId = ui.sockets[0].sent[0].session.sessionId;
    ui.sockets[0].close();
    const reconnect = [...ui.timers.values()][0];
    reconnect();
    ui.sockets[1].open();
    const secondId = ui.messages.filter((message) => message.type === "connection-metadata-request").at(-1).sessionId;
    assert.notEqual(firstId, secondId);
    await ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: firstId, fileId: "old", fileName: "Old file" },
    });
    assert.deepEqual(ui.sockets[1].sent, []);
    await ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: secondId, fileId: "file-1", fileName: "Design" },
    });
    assert.equal(ui.sockets[1].sent[0].session.sessionId, sessionId);
    assert.notEqual(ui.sockets[1].sent[0].session.sessionId, secondId);
});

test("resume and freeze events cannot send liveness messages before initialization", () => {
    const ui = pluginUi();
    ui.connect();
    ui.events.get("resume")!();
    ui.events.get("freeze")!();
    assert.deepEqual(ui.sockets[0].sent, []);
    ui.sockets[0].receive({ type: "initialized" });
    ui.events.get("resume")!();
    assert.deepEqual(ui.sockets[0].sent, [{ type: "heartbeat" }]);
});

test("plugin reads the current file for each connection metadata request", () => {
    let onMessage!: (message: any) => void;
    const messages: any[] = [];
    const penpot = {
        currentFile: { id: "file-1", name: "First design" },
        currentUser: { sessionId: "tab-1" },
        theme: "dark",
        ui: {
            open() {},
            onMessage: (handler: (message: any) => void) => {
                onMessage = handler;
            },
            sendMessage: (message: any) => messages.push(JSON.parse(JSON.stringify(message))),
        },
        on() {},
    };
    runInNewContext(compile("plugin.ts"), {
        exports: {},
        penpot,
        mcp: undefined,
        require: () => ({ ExecuteCodeTaskHandler: class {} }),
    });
    onMessage({ type: "connection-metadata-request", sessionId: "first" });
    penpot.currentFile = { id: "file-2", name: "Second design" };
    onMessage({ type: "connection-metadata-request", sessionId: "second" });
    assert.deepEqual(messages, [
        {
            type: "initialize",
            tabId: "tab-1",
            session: { sessionId: "first", fileId: "file-1", fileName: "First design" },
        },
        {
            type: "initialize",
            tabId: "tab-1",
            session: { sessionId: "second", fileId: "file-2", fileName: "Second design" },
        },
    ]);
});

test("integrated connections reuse the short ID across reconnects", async () => {
    const ui = pluginUi();
    ui.message({ type: "mcp-mode", integratedRemoteMcp: true });
    const initialize = async (requestId: string) => {
        await ui.message({
            type: "initialize",
            tabId: "tab-1",
            session: { sessionId: requestId, fileId: "file-1", fileName: "Design" },
        });
    };
    await initialize(ui.connect());
    assert.equal(ui.sockets[0].sent[0].session.sessionId, "pq3gxqddgj");
    ui.sockets[0].receive({ type: "initialized" });
    assert.equal(ui.messages.at(-1).sessionId, "pq3gxqddgj");
    ui.sockets[0].close();
    [...ui.timers.values()][0]();
    ui.sockets[1].open();
    const requestId = ui.messages.filter((message) => message.type === "connection-metadata-request").at(-1).sessionId;
    await initialize(requestId);
    assert.equal(ui.sockets[1].sent[0].session.sessionId, "pq3gxqddgj");
});

test("disconnect cancels metadata initialization and ignores a late acknowledgement", async () => {
    const ui = pluginUi();
    ui.message({ type: "mcp-mode", integratedRemoteMcp: true });
    const requestId = ui.connect();
    const pending = ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: requestId, fileId: "file-1", fileName: "Design" },
    });
    ui.message({ type: "stop-server" });
    await pending;
    ui.sockets[0].receive({ type: "initialized" });
    assert.deepEqual(ui.sockets[0].sent, []);
    assert.equal(ui.messages.at(-1).status, "disconnected");
    assert.equal(ui.timers.size, 0);
});

test("integrated plugin stays idle until explicitly connected, including while the UI loads", () => {
    let onMessage!: (message: any) => void;
    const events = new Map<string, () => void>();
    const messages: any[] = [];
    const statuses: string[] = [];
    runInNewContext(compile("plugin.ts"), {
        exports: {},
        console: { log() {} },
        PENPOT_MCP_VERSION: "0.0.0",
        penpot: {
            theme: "dark",
            version: "0.0.0",
            ui: {
                open() {},
                onMessage: (handler: (message: any) => void) => {
                    onMessage = handler;
                },
                sendMessage: (message: any) => messages.push(message),
            },
            on() {},
        },
        mcp: {
            getToken: () => "test-token",
            getServerUrl: () => "ws://localhost:4402",
            isConnectionRequested: () => false,
            setMcpStatus: (status: string) => statuses.push(status),
            on: (event: string, callback: () => void) => events.set(event, callback),
        },
        require: () => ({ ExecuteCodeTaskHandler: class {} }),
    });
    assert.ok(!statuses.includes("connecting"));
    events.get("connect")!();
    events.get("disconnect")!();
    onMessage({ type: "ui-initialized" });
    assert.ok(!messages.some((message) => message.type === "start-server"));
    events.get("connect")!();
    assert.equal(messages.at(-1).type, "start-server");
});

test("standalone session ID survives reconnects and plugin restarts in the same app instance", async () => {
    const ui = pluginUi();
    const firstRequestId = ui.connect();
    await ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: firstRequestId, fileId: "file-1", fileName: "Design" },
    });
    const sessionId = ui.sockets[0].sent[0].session.sessionId;
    ui.message({ type: "stop-server" });
    assert.equal(ui.messages.at(-1).sessionId, null);
    const secondRequestId = ui.connect();
    await ui.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: secondRequestId, fileId: "file-1", fileName: "Design" },
    });
    assert.equal(ui.sockets[1].sent[0].session.sessionId, sessionId);
    ui.sockets[1].receive({ type: "initialized" });
    assert.equal(ui.messages.at(-1).sessionId, sessionId);

    const next = pluginUi();
    const nextRequestId = next.connect();
    await next.message({
        type: "initialize",
        tabId: "tab-1",
        session: { sessionId: nextRequestId, fileId: "file-1", fileName: "Design" },
    });
    assert.equal(next.sockets[0].sent[0].session.sessionId, sessionId);
});
