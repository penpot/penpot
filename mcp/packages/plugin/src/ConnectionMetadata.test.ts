import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";
import ts from "typescript";
import { shouldReconnectAfterClose } from "./ReconnectPolicy.ts";

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

test("sends file metadata before marking a plugin connection ready", () => {
    const ui = pluginUi();
    const sessionId = ui.connect();
    const initialization = { type: "initialize", session: { sessionId, fileId: "file-1", fileName: "Design" } };
    ui.message(initialization);
    assert.deepEqual(ui.sockets[0].sent, [initialization]);
    assert.equal(
        ui.messages.some((message) => message.status === "connected"),
        false
    );
    ui.sockets[0].receive({ type: "initialized" });
    assert.equal(ui.messages.at(-1).status, "connected");
});

test("reconnects with a fresh session ID and ignores metadata for the old socket", () => {
    const ui = pluginUi();
    const firstId = ui.connect();
    ui.sockets[0].close();
    const reconnect = [...ui.timers.values()][0];
    reconnect();
    ui.sockets[1].open();
    const secondId = ui.messages.filter((message) => message.type === "connection-metadata-request").at(-1).sessionId;
    assert.notEqual(firstId, secondId);
    ui.message({ type: "initialize", session: { sessionId: firstId, fileId: "old", fileName: "Old file" } });
    assert.deepEqual(ui.sockets[1].sent, []);
    ui.message({ type: "initialize", session: { sessionId: secondId, fileId: "new", fileName: "New file" } });
    assert.equal(ui.sockets[1].sent[0].session.sessionId, secondId);
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
        { type: "initialize", session: { sessionId: "first", fileId: "file-1", fileName: "First design" } },
        { type: "initialize", session: { sessionId: "second", fileId: "file-2", fileName: "Second design" } },
    ]);
});
