import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { stripTypeScriptTypes } from "node:module";
import test from "node:test";
import vm from "node:vm";
import { shouldReconnectAfterClose } from "./ReconnectPolicy.ts";
import { SessionId } from "./SessionId.ts";
import { webcrypto } from "node:crypto";

const source = stripTypeScriptTypes(
    readFileSync(new URL("./main.ts", import.meta.url), "utf8").replace(/^import .*;\r?\n/gm, "")
);

function browser() {
    let now = 0;
    let nextTimer = 0;
    const timers = new Map<number, { at: number; fn: () => void; interval: number }>();
    const sockets: Socket[] = [];
    const messages: any[] = [];
    const events: Record<string, (event?: any) => void> = {};
    const nodes = new Map<string, any>();
    const setTimer = (fn: () => void, delay: number, interval = 0) => {
        const id = ++nextTimer;
        timers.set(id, { at: now + delay, fn, interval });
        return id;
    };
    class Socket {
        static CONNECTING = 0;
        static OPEN = 1;
        static CLOSING = 2;
        static CLOSED = 3;
        readyState = Socket.CONNECTING;
        sent: string[] = [];
        onopen?: () => void;
        onclose?: (event: any) => void;
        onerror?: (event: any) => void;
        onmessage?: (event: any) => void;
        url: string;
        constructor(url: string) {
            this.url = url;
            sockets.push(this);
        }
        send(message: string) {
            this.sent.push(message);
        }
        // Closing need not deliver onclose before another connection is attempted.
        close() {
            this.readyState = Socket.CLOSING;
        }
        open() {
            this.readyState = Socket.OPEN;
            this.onopen?.();
            this.onmessage?.({ data: JSON.stringify({ type: "initialized" }) });
        }
        closed(code = 1006) {
            this.readyState = Socket.CLOSED;
            this.onclose?.({ code, reason: "" });
        }
    }
    vm.runInNewContext(source, {
        shouldReconnectAfterClose,
        SessionId,
        crypto: webcrypto,
        PENPOT_MCP_WEBSOCKET_URL: "ws://localhost:4402",
        URLSearchParams,
        WebSocket: Socket,
        console: { log() {}, warn() {}, error() {} },
        window: {
            location: { hash: "" },
            addEventListener: (name: string, fn: any) => {
                events[name] = fn;
            },
        },
        document: {
            body: { dataset: {} },
            visibilityState: "visible",
            addEventListener: (name: string, fn: any) => {
                events[name] = fn;
            },
            getElementById: (id: string) => {
                if (id === "session-id") return null;
                if (!nodes.has(id)) nodes.set(id, { dataset: {}, addEventListener() {} });
                return nodes.get(id);
            },
        },
        parent: { postMessage: (message: any) => messages.push(message) },
        setTimeout: setTimer,
        clearTimeout: (id: number) => timers.delete(id),
        setInterval: (fn: () => void, delay: number) => setTimer(fn, delay, delay),
        clearInterval: (id: number) => timers.delete(id),
    });
    const emit = (type: string, data = {}) => events.message({ data: { type, ...data } });
    const tick = (ms: number) => {
        const end = now + ms;
        while (true) {
            const due = [...timers].sort((a, b) => a[1].at - b[1].at).find(([, timer]) => timer.at <= end);
            if (!due) break;
            const [id, timer] = due;
            now = timer.at;
            if (timer.interval) timer.at += timer.interval;
            else timers.delete(id);
            timer.fn();
        }
        now = end;
    };
    const start = () => emit("start-server", { url: "wss://example.invalid/mcp", token: "test token" });
    const status = () => nodes.get("connection-status").dataset.status;
    return { sockets, messages, events, emit, tick, start, status };
}

test("a stalled handshake times out and retries with the same URL and token without a close event", () => {
    const b = browser();
    b.start();
    b.tick(29_999);
    b.events.resume();
    b.events.visibilitychange();
    b.start();
    assert.equal(b.sockets.length, 1);
    b.tick(1);
    assert.equal(b.sockets[0].readyState, 2);
    assert.equal(b.status(), "error");
    b.tick(999);
    assert.equal(b.sockets.length, 1);
    b.tick(1);
    assert.equal(b.sockets.length, 2);
    assert.equal(b.sockets[1].url, "wss://example.invalid/mcp?userToken=test%20token");
    b.tick(30_000 + 1_999);
    assert.equal(b.sockets.length, 2);
    b.tick(1);
    assert.equal(b.sockets.length, 3);
});

test("late events from an expired socket cannot replace the new connection or forward tasks", () => {
    const b = browser();
    b.start();
    const old = b.sockets[0];
    b.tick(31_000);
    const active = b.sockets[1];
    active.open();
    b.tick(100);
    const count = b.messages.length;
    old.open();
    old.onerror?.({});
    old.onmessage?.({ data: JSON.stringify({ id: "old-task", task: "execute-code" }) });
    old.closed(1008);
    b.tick(100);
    assert.equal(b.status(), "connected");
    assert.equal(b.messages.length, count);
    b.tick(30_000);
    assert.equal(b.sockets.length, 2);
    assert.ok(active.sent.length > 0);
    assert.ok(active.sent.every((message) => JSON.parse(message).type === "heartbeat"));
});

test("disconnect cancels connecting deadlines, backoff and late initialization", () => {
    for (const stage of ["connecting", "backoff", "opened"]) {
        const b = browser();
        b.start();
        const socket = b.sockets[0];
        if (stage === "backoff") b.tick(30_000);
        if (stage === "opened") socket.open();
        b.emit("stop-server");
        socket.open();
        b.tick(120_000);
        b.events.resume();
        b.events.visibilitychange();
        assert.equal(b.sockets.length, 1, stage);
        assert.equal(b.status(), "disconnected", stage);
        assert.equal(socket.sent.length, 0, stage);
    }
});

test("opening cancels its deadline and transient close recovers without replaying tasks", () => {
    const b = browser();
    b.start();
    b.tick(29_000);
    b.sockets[0].open();
    b.tick(31_000);
    assert.equal(b.status(), "connected");
    assert.equal(b.sockets.length, 1);
    b.sockets[0].onmessage?.({ data: JSON.stringify({ id: "task", task: "execute-code" }) });
    b.sockets[0].closed();
    b.tick(1_000);
    b.sockets[1].open();
    b.tick(100);
    assert.equal(b.status(), "connected");
    assert.equal(b.messages.filter((message) => message.id === "task").length, 1);
    assert.equal(b.sockets[1].sent.length, 0);
});

test("policy rejection stops automatic recovery until explicitly connected again", () => {
    const b = browser();
    b.start();
    b.sockets[0].open();
    b.sockets[0].closed(1008);
    b.tick(120_000);
    b.events.resume();
    b.events.visibilitychange();
    assert.equal(b.sockets.length, 1);
    assert.equal(b.status(), "disconnected");
    b.start();
    assert.equal(b.sockets.length, 2);
});

test("late close of a replaced closing socket cannot stop a live connection", () => {
    const b = browser();
    b.start();
    const old = b.sockets[0];
    old.open();
    old.close();
    b.start();
    const active = b.sockets[1];
    active.open();
    old.closed(1008);
    b.tick(30_000);
    assert.equal(b.status(), "connected");
    assert.equal(b.sockets.length, 2);
    assert.ok(active.sent.length > 0);
});

test("repeated stalled handshakes retain capped exponential backoff", () => {
    const b = browser();
    b.start();
    for (const [index, delay] of [1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000].entries()) {
        b.tick(30_000 + delay - 1);
        assert.equal(b.sockets.length, index + 1);
        b.tick(1);
        assert.equal(b.sockets.length, index + 2);
    }
});

test("a queued policy close is not replaced by a handshake timeout", () => {
    for (const readyState of [2, 3]) {
        const b = browser();
        b.start();
        const socket = b.sockets[0];
        socket.readyState = readyState;
        b.tick(31_000);
        assert.equal(b.sockets.length, 1);
        socket.closed(1008);
        b.tick(120_000);
        assert.equal(b.sockets.length, 1);
        assert.equal(b.status(), "disconnected");
    }
});

test("an open socket waiting for its open event is not timed out", () => {
    const b = browser();
    b.start();
    b.sockets[0].readyState = 1;
    b.tick(31_000);
    assert.equal(b.sockets.length, 1);
    assert.equal(b.sockets[0].readyState, 1);
    b.sockets[0].open();
    b.tick(100);
    assert.equal(b.status(), "connected");
});

test("resuming after a frozen connection drops recovers a stalled retry without restarting", () => {
    const b = browser();
    b.start();
    b.sockets[0].open();
    b.events.freeze();
    assert.equal(JSON.parse(b.sockets[0].sent.at(-1)!).type, "freeze");
    b.sockets[0].closed();
    b.tick(1_000);
    assert.equal(b.sockets.length, 2);
    b.events.resume();
    b.events.visibilitychange();
    b.tick(30_000 + 2_000);
    assert.equal(b.sockets.length, 3);
    b.sockets[2].open();
    b.events.resume();
    assert.equal(b.status(), "connected");
    assert.equal(JSON.parse(b.sockets[2].sent.at(-1)!).type, "heartbeat");
    assert.equal(b.sockets[2].url, b.sockets[0].url);
});
