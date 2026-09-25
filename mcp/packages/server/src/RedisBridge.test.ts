import assert from "node:assert/strict";
import { once } from "node:events";
import { beforeEach, test, type TestContext } from "node:test";
import Redis, { type Command } from "ioredis";
import { WebSocket } from "ws";
import { RedisBridge } from "./RedisBridge";
import { PluginBridge } from "./PluginBridge";
import { PluginTask } from "./PluginTask";
import type { PenpotMcpServer } from "./PenpotMcpServer";

// Replace only the Redis command boundary; exercise the real transport,
// dispatchers, task tracking, and WebSockets above it.
let subscriptions: Map<string, Set<Redis>>;
let failDiscoverySubscription: boolean;
beforeEach((context) => {
    // These hooks run for tests, so mocks live through each test's cleanup hooks.
    const { mock } = context as TestContext;
    subscriptions = new Map();
    failDiscoverySubscription = false;
    mock.method(Redis.prototype, "connect", async function (this: Redis) {
        this.status = "ready";
    });
    mock.method(Redis.prototype, "sendCommand", async function (this: Redis, command: Command) {
        const [channel, message] = command.args.map(String);
        if (command.name === "subscribe") {
            const subscribers = subscriptions.get(channel) ?? new Set();
            subscribers.add(this);
            subscriptions.set(channel, subscribers);
            if (failDiscoverySubscription && channel.includes(".discovery.req.")) {
                failDiscoverySubscription = false;
                throw new Error("Subscription failed");
            }
            return subscribers.size;
        }
        if (command.name === "unsubscribe") {
            subscriptions.get(channel)?.delete(this);
            return 0;
        }
        if (command.name === "publish") {
            const subscribers = [...(subscriptions.get(channel) ?? [])];
            for (const subscriber of subscribers) subscriber.emit("message", channel, message);
            return subscribers.length;
        }
        if (command.name === "quit") {
            for (const subscribers of subscriptions.values()) subscribers.delete(this);
            return "OK";
        }
        throw new Error(`Unexpected Redis command: ${command.name}`);
    });
});

function transport(t: TestContext, tenant = "test") {
    const bridge = new RedisBridge("redis://localhost:6379", tenant);
    t.after(() => bridge.close());
    return bridge;
}

test("Redis task channels isolate users, sessions, and tenants without delimiter collisions", async (t) => {
    const owner = transport(t);
    const caller = transport(t);
    const otherTenant = transport(t, "other");
    const delivered: unknown[] = [];
    await owner.subscribeToTasks("alice.one", "two", (request) => {
        owner.publishTaskResponse(request.id, { id: request.id, success: true, data: "selected" });
    });
    for (const [token, session] of [
        ["alice", "one.two"],
        ["bob", "two"],
        ["alice.one", "other"],
    ]) {
        assert.equal(
            await caller.sendTaskRequest(token, session, { id: "missing", task: "test", params: {} }, () => {}),
            0
        );
    }
    assert.equal(
        await otherTenant.sendTaskRequest(
            "alice.one",
            "two",
            { id: "other-tenant", task: "test", params: {} },
            () => {}
        ),
        0
    );
    assert.equal(
        await caller.sendTaskRequest("alice.one", "two", { id: "selected", task: "test", params: {} }, (response) =>
            delivered.push(response.data)
        ),
        1
    );
    assert.deepEqual(delivered, ["selected"]);
});

test("Redis discovery receives one reply per subscribed instance and returns publish's recipient count", async (t) => {
    const first = transport(t);
    const second = transport(t);
    const requester = transport(t);
    await first.subscribeToDiscovery("alice", ({ id }) => {
        first.publishDiscoveryResponse(id, {
            instanceId: "first",
            sessions: [{ sessionId: "one", fileId: "file", fileName: "Design" }],
        });
    });
    await second.subscribeToDiscovery("alice", ({ id }) => {
        second.publishDiscoveryResponse(id, { instanceId: "second", sessions: [] });
    });
    const responders: string[] = [];
    const count = await requester.sendDiscoveryRequest("alice", "query", (response) =>
        responders.push(response.instanceId)
    );
    assert.equal(count, 2);
    assert.deepEqual(responders.sort(), ["first", "second"]);
    await requester.unsubscribeFromDiscoveryResponses("query");
    first.publishDiscoveryResponse("query", { instanceId: "late", sessions: [] });
    assert.deepEqual(responders.sort(), ["first", "second"]);
});

test("unsubscribing a session retains the user's other task channels", async (t) => {
    const owner = transport(t);
    const caller = transport(t);
    await owner.subscribeToTasks("alice", "first", () => {});
    await owner.subscribeToTasks("alice", "second", () => {});
    await owner.unsubscribeFromTasks("alice", "first");
    assert.equal(
        await caller.sendTaskRequest("alice", "first", { id: "first", task: "test", params: {} }, () => {}),
        0
    );
    assert.equal(
        await caller.sendTaskRequest("alice", "second", { id: "second", task: "test", params: {} }, () => {}),
        1
    );
    await caller.unsubscribeFromResponse("second");
});

test("a failed discovery subscription is cleaned up and can be retried", async (t) => {
    const owner = transport(t);
    const caller = transport(t);
    failDiscoverySubscription = true;
    await assert.rejects(
        owner.subscribeToDiscovery("alice", () => {}),
        /Subscription failed/
    );
    assert.equal(await caller.sendDiscoveryRequest("alice", "after-failure", () => {}), 0);
    await caller.unsubscribeFromDiscoveryResponses("after-failure");
    await owner.subscribeToDiscovery("alice", ({ id }) => {
        owner.publishDiscoveryResponse(id, { instanceId: "owner", sessions: [] });
    });
    assert.equal(await caller.sendDiscoveryRequest("alice", "retry", () => {}), 1);
    await caller.unsubscribeFromDiscoveryResponses("retry");
});

let port = 18_700;
function instance(t: TestContext) {
    const redis = new RedisBridge("redis://localhost:6379", "test");
    const wsPort = ++port;
    const bridge = new PluginBridge(
        {
            host: "127.0.0.1",
            isMultiUserMode: () => true,
            getSessionContext: () => ({ userToken: "alice" }),
        } as PenpotMcpServer,
        wsPort,
        0.1,
        redis
    );
    const sockets: WebSocket[] = [];
    t.after(async () => {
        for (const socket of sockets) socket.terminate();
        await bridge.close();
        await redis.close();
    });
    const connect = async (sessionId: string, outcome: "success" | "error" | "silent" = "success") => {
        const socket = new WebSocket(`ws://127.0.0.1:${wsPort}?userToken=alice`);
        sockets.push(socket);
        await once(socket, "open");
        const initialized = once(socket, "message", { signal: AbortSignal.timeout(2000) });
        socket.send(JSON.stringify({ type: "initialize", session: { sessionId, fileId: "file", fileName: "Design" } }));
        await initialized;
        socket.on("message", (raw) => {
            const request = JSON.parse(raw.toString());
            if (outcome !== "silent") {
                socket.send(
                    JSON.stringify({
                        id: request.id,
                        success: outcome === "success",
                        data: sessionId,
                        error: "Plugin execution failed",
                    })
                );
            }
        });
    };
    return { bridge, connect };
}

test("full Redis forwarding resolves the selected WebSocket's result", async (t) => {
    const owner = instance(t);
    const requester = instance(t);
    await owner.connect("first");
    await owner.connect("second");
    assert.equal((await requester.bridge.executePluginTask(new PluginTask("test", {}), "second")).data, "second");
    assert.equal((await owner.bridge.executePluginTask(new PluginTask("test", {}), "first")).data, "first");
});

test("full Redis discovery implicitly selects a sole remote WebSocket", async (t) => {
    const owner = instance(t);
    const requester = instance(t);
    await owner.connect("only");
    assert.equal((await requester.bridge.executePluginTask(new PluginTask("test", {}))).data, "only");
});

test("forwarded plugin failures retain the regular execution failure route", async (t) => {
    const owner = instance(t);
    const requester = instance(t);
    await owner.connect("failing", "error");
    await assert.rejects(
        requester.bridge.executePluginTask(new PluginTask("test", {}), "failing"),
        /Plugin execution failed/
    );
});

test("forwarded task timeouts release their Redis response subscriptions", async (t) => {
    const owner = instance(t);
    const requester = instance(t);
    await owner.connect("silent", "silent");
    await assert.rejects(requester.bridge.executePluginTask(new PluginTask("test", {}), "silent"), /timed out/);
    const responseSubscriptions = [...subscriptions].filter(([channel]) => channel.includes(".task.res."));
    assert.ok(responseSubscriptions.every(([, subscribers]) => subscribers.size === 0));
});
