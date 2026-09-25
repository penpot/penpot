import assert from "node:assert/strict";
import { once } from "node:events";
import { afterEach, beforeEach, test } from "node:test";
import { WebSocket } from "ws";
import { PluginBridge } from "./PluginBridge";
import { PluginTask } from "./PluginTask";
import type { PenpotMcpServer } from "./PenpotMcpServer";

let bridge: PluginBridge;
let port = 17_600;
let userToken: string | undefined;
let multiUser: boolean;
let sockets: WebSocket[];

beforeEach(() => {
    port++;
    userToken = "alice";
    multiUser = true;
    sockets = [];
    bridge = new PluginBridge(
        {
            host: "127.0.0.1",
            isMultiUserMode: () => multiUser,
            getSessionContext: () => ({ userToken }),
        } as PenpotMcpServer,
        port,
        1
    );
});

afterEach(async () => {
    for (const socket of sockets) socket.terminate();
    await bridge.close();
});

async function connect(sessionId: string, token = "alice", fileName = "Design") {
    const socket = new WebSocket(`ws://127.0.0.1:${port}?userToken=${token}`);
    sockets.push(socket);
    await once(socket, "open");
    const initialized = once(socket, "message", { signal: AbortSignal.timeout(1500) });
    socket.send(JSON.stringify({ type: "initialize", session: { sessionId, fileId: "file-1", fileName } }));
    const [message] = await initialized;
    assert.equal(JSON.parse(message.toString()).type, "initialized");
    socket.on("message", (raw) => {
        const request = JSON.parse(raw.toString());
        socket.send(JSON.stringify({ id: request.id, success: true, data: sessionId }));
    });
    return socket;
}

test("keeps two tabs for the same file and dispatches to the selected session", async () => {
    await connect("tab-1");
    await connect("tab-2");
    const result = await bridge.executePluginTask(new PluginTask("test", {}), "tab-2");
    assert.equal(result.data, "tab-2");
});

test("requires user selection when more than one session is connected", async () => {
    await connect("tab-1", "alice", "First design");
    await connect("tab-2", "alice", "Second design");
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {})), (error: Error) => {
        assert.match(error.message, /Ask the user/);
        assert.match(error.message, /tab-1.*First design/);
        assert.match(error.message, /tab-2.*Second design/);
        return true;
    });
});

test("implicitly selects the only session belonging to the requesting user", async () => {
    await connect("alice-tab");
    await connect("bob-tab", "bob");
    const result = await bridge.executePluginTask(new PluginTask("test", {}));
    assert.equal(result.data, "alice-tab");
});

test("does not dispatch to another user's session", async () => {
    await connect("alice-tab");
    await connect("bob-tab", "bob");
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "bob-tab"), /not connected/);
});

test("reports no sessions when no plugin has initialized", async () => {
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {})), /No Penpot.*connected/);
});

test("regular plugins without user tokens can select among multiple sessions", async () => {
    multiUser = false;
    await connect("first", "");
    await connect("second", "");
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {})), /Ask the user/);
    assert.equal((await bridge.executePluginTask(new PluginTask("test", {}), "second")).data, "second");
});

test("disconnecting one session leaves its sibling available for implicit selection", async () => {
    const first = await connect("first");
    await connect("second");
    const closed = once(first, "close");
    first.close();
    await closed;
    assert.equal((await bridge.executePluginTask(new PluginTask("test", {}))).data, "second");
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "first"), /not connected/);
});

test("rejecting a duplicate session ID does not remove the original connection", async () => {
    await connect("original");
    const duplicate = new WebSocket(`ws://127.0.0.1:${port}?userToken=alice`);
    sockets.push(duplicate);
    await once(duplicate, "open");
    const closed = once(duplicate, "close");
    duplicate.send(
        JSON.stringify({
            type: "initialize",
            session: { sessionId: "original", fileId: "file", fileName: "Duplicate" },
        })
    );
    assert.equal((await closed)[0], 1008);
    assert.equal((await bridge.executePluginTask(new PluginTask("test", {}), "original")).data, "original");
});

test("uninitialized sockets are not candidates for discovery", async () => {
    await connect("ready");
    const pending = new WebSocket(`ws://127.0.0.1:${port}?userToken=alice`);
    sockets.push(pending);
    await once(pending, "open");
    assert.equal((await bridge.executePluginTask(new PluginTask("test", {}))).data, "ready");
});

test("invalid initialization metadata cannot register a session", async () => {
    const socket = new WebSocket(`ws://127.0.0.1:${port}?userToken=alice`);
    sockets.push(socket);
    await once(socket, "open");
    const closed = once(socket, "close");
    socket.send(JSON.stringify({ type: "initialize", session: { sessionId: "bad", fileId: 42 } }));
    assert.equal((await closed)[0], 1008);
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {})), /No Penpot sessions/);
});
