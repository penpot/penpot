import assert from "node:assert/strict";
import test from "node:test";
import type { PenpotSession, PluginTaskRequest, PluginTaskResponse } from "@penpot/mcp-common";
import type { PenpotConnection } from "../PenpotConnection";
import { PluginTask, type AbstractPluginTask } from "../PluginTask";
import type { RedisBridge, SessionDiscoveryRequest, SessionDiscoveryResponse } from "../RedisBridge";
import { UserPenpotConnections } from "../UserPenpotConnections";
import { MultiInstanceTaskDispatcher } from "./MultiInstanceTaskDispatcher";
import { SessionDiscoveryError, type TaskDispatchHost } from "./TaskDispatcher";

/** In-memory Pub/Sub transport retaining Redis's per-subscriber delivery count. */
class RedisNetwork {
    readonly tasks = new Map<string, Map<RedisPeer, (request: PluginTaskRequest) => void>>();
    readonly discovery = new Map<string, Map<RedisPeer, (request: SessionDiscoveryRequest) => void>>();
    readonly taskResponses = new Map<string, (response: PluginTaskResponse<unknown>) => void>();
    readonly discoveryResponses = new Map<string, (response: SessionDiscoveryResponse) => void>();
    failDiscovery = false;
    beforeDiscoveryPublish?: () => Promise<void>;
}

/** One MCP instance's connection to the test Pub/Sub network. */
class RedisPeer {
    replyToDiscovery = true;
    repeatDiscoveryReplies = false;

    constructor(readonly network: RedisNetwork) {}

    asBridge(): RedisBridge {
        return this as unknown as RedisBridge;
    }

    async subscribeToTasks(token: string, session: string, handler: (request: PluginTaskRequest) => void) {
        const key = JSON.stringify([token, session]);
        const subscribers = this.network.tasks.get(key) ?? new Map();
        subscribers.set(this, handler);
        this.network.tasks.set(key, subscribers);
    }

    async unsubscribeFromTasks(token: string, session: string) {
        this.network.tasks.get(JSON.stringify([token, session]))?.delete(this);
    }

    async sendTaskRequest(
        token: string,
        session: string,
        request: PluginTaskRequest,
        onResponse: (response: PluginTaskResponse<unknown>) => void
    ) {
        this.network.taskResponses.set(request.id, onResponse);
        const subscribers = this.network.tasks.get(JSON.stringify([token, session])) ?? new Map();
        for (const handler of subscribers.values()) handler(request);
        if (!subscribers.size) this.network.taskResponses.delete(request.id);
        return subscribers.size;
    }

    publishTaskResponse(id: string, response: PluginTaskResponse<unknown>) {
        const handler = this.network.taskResponses.get(id);
        this.network.taskResponses.delete(id);
        handler?.(response);
    }

    async subscribeToDiscovery(token: string, handler: (request: SessionDiscoveryRequest) => void) {
        const subscribers = this.network.discovery.get(token) ?? new Map();
        subscribers.set(this, handler);
        this.network.discovery.set(token, subscribers);
    }

    async unsubscribeFromDiscovery(token: string) {
        this.network.discovery.get(token)?.delete(this);
    }

    async sendDiscoveryRequest(token: string, id: string, onResponse: (response: SessionDiscoveryResponse) => void) {
        if (this.network.failDiscovery) throw new Error("Redis unavailable for discovery");
        this.network.discoveryResponses.set(id, onResponse);
        await this.network.beforeDiscoveryPublish?.();
        const subscribers = this.network.discovery.get(token) ?? new Map();
        for (const [peer, handler] of subscribers) {
            if (peer.replyToDiscovery) {
                handler({ id });
                if (peer.repeatDiscoveryReplies) handler({ id });
            }
        }
        return subscribers.size;
    }

    publishDiscoveryResponse(id: string, response: SessionDiscoveryResponse) {
        this.network.discoveryResponses.get(id)?.(response);
    }

    async unsubscribeFromDiscoveryResponses(id: string) {
        this.network.discoveryResponses.delete(id);
    }
}

/** Local registry and execution endpoint used with the real dispatchers. */
class Instance implements TaskDispatchHost {
    readonly users = new Map<string, UserPenpotConnections>();
    readonly redis: RedisPeer;
    readonly dispatcher: MultiInstanceTaskDispatcher;
    readonly executions: string[] = [];

    constructor(network: RedisNetwork) {
        this.redis = new RedisPeer(network);
        this.dispatcher = new MultiInstanceTaskDispatcher(this, this.redis.asBridge(), 20);
    }

    async connect(token: string, sessionId: string, fileName = "Design") {
        const session: PenpotSession = { sessionId, fileId: "same-file", fileName };
        const connection = { userToken: token, session, ready: false, socket: { readyState: 1 } } as PenpotConnection;
        const connections = this.users.get(token) ?? new UserPenpotConnections();
        this.users.set(token, connections);
        connections.add(connection);
        await this.dispatcher.onNewConnection(connection);
        connection.ready = true;
        return connection;
    }

    async disconnect(connection: PenpotConnection) {
        this.users.get(connection.userToken!)!.remove(connection);
        connection.ready = false;
        await this.dispatcher.onConnectionClosed(connection);
    }

    getUserConnections(token: string | null) {
        return this.users.get(token!);
    }

    sendLocalTask(task: AbstractPluginTask, connection: PenpotConnection) {
        if (!connection.ready) throw new Error("Plugin disconnected");
        this.executions.push(connection.session.sessionId);
        task.resolveWithResult({ data: connection.session.sessionId });
    }

    sendRemoteTask(task: AbstractPluginTask, token: string, sessionId: string) {
        void this.redis
            .sendTaskRequest(token, sessionId, task.toRequest(), (response) => {
                if (response.success) task.resolveWithResult({ data: response.data });
                else task.rejectWithError(new Error(response.error));
            })
            .then((count) => {
                if (!count) task.rejectWithError(new Error("Session not connected"));
            });
    }

    async execute(token: string, sessionId?: string) {
        const task = new PluginTask("test", {});
        await this.dispatcher.dispatch(task, token, sessionId);
        return await task.getResultPromise();
    }
}

test("discovers all sessions across instances, including several tabs for one file", async () => {
    const network = new RedisNetwork();
    const first = new Instance(network);
    const second = new Instance(network);
    await first.connect("alice", "one");
    await first.connect("alice", "two");
    await second.connect("alice", "three");
    await second.connect("bob", "private");
    const sessions = await new Instance(network).dispatcher.discoverSessions("alice");
    assert.deepEqual(sessions.map((session) => session.sessionId).sort(), ["one", "three", "two"]);
    assert.equal(network.discoveryResponses.size, 0);
});

test("explicit sessions dispatch remotely without discovery", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    const requester = new Instance(network);
    await owner.connect("alice", "selected");
    network.failDiscovery = true;
    assert.equal((await requester.execute("alice", "selected")).data, "selected");
});

test("explicit sessions work through Redis on their owning instance too", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    await owner.connect("alice", "selected");
    network.failDiscovery = true;
    assert.equal((await owner.execute("alice", "selected")).data, "selected");
});

test("explicit session IDs cannot route tasks across user boundaries", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    await owner.connect("bob", "private");
    await assert.rejects(new Instance(network).execute("alice", "private"), /not connected/);
    assert.deepEqual(owner.executions, []);
});

test("complete discovery dispatches when exactly one session exists", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    await owner.connect("alice", "only");
    assert.equal((await new Instance(network).execute("alice")).data, "only");
});

test("complete discovery requires selection when more than one session exists", async () => {
    const network = new RedisNetwork();
    await new Instance(network).connect("alice", "first", "First file");
    await new Instance(network).connect("alice", "second", "Second file");
    await assert.rejects(new Instance(network).execute("alice"), (error: Error) => {
        assert.match(error.message, /Ask the user/);
        assert.match(error.message, /first.*First file/);
        assert.match(error.message, /second.*Second file/);
        return true;
    });
});

test("zero discovery subscribers reports no connected sessions", async () => {
    await assert.rejects(new Instance(new RedisNetwork()).execute("alice"), /No Penpot sessions/);
});

test("incomplete discovery never dispatches even when it yields exactly one session", async () => {
    const network = new RedisNetwork();
    const responsive = new Instance(network);
    const slow = new Instance(network);
    await responsive.connect("alice", "visible");
    await slow.connect("alice", "hidden");
    slow.redis.replyToDiscovery = false;
    await assert.rejects(new Instance(network).execute("alice"), (error: Error) => {
        assert.ok(error instanceof SessionDiscoveryError);
        assert.match(error.message, /temporary server load.*retry/);
        return true;
    });
    assert.deepEqual(responsive.executions, []);
    assert.equal(network.discoveryResponses.size, 0);
});

test("duplicate replies from one instance do not substitute for a missing reply", async () => {
    const network = new RedisNetwork();
    const responsive = new Instance(network);
    const slow = new Instance(network);
    await responsive.connect("alice", "visible");
    await slow.connect("alice", "hidden");
    responsive.redis.repeatDiscoveryReplies = true;
    slow.redis.replyToDiscovery = false;
    await assert.rejects(new Instance(network).execute("alice"), SessionDiscoveryError);
});

test("discovery transport failures use the retryable discovery error", async () => {
    const network = new RedisNetwork();
    network.failDiscovery = true;
    await assert.rejects(new Instance(network).execute("alice"), SessionDiscoveryError);
});

test("disconnecting one tab retains discovery and dispatch for the remaining tab", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    const first = await owner.connect("alice", "first");
    await owner.connect("alice", "second");
    await owner.disconnect(first);
    assert.equal((await new Instance(network).execute("alice")).data, "second");
});

test("disconnecting the last tab removes its discovery subscription", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    const connection = await owner.connect("alice", "only");
    await owner.disconnect(connection);
    assert.equal(network.discovery.get("alice")?.size, 0);
    await assert.rejects(new Instance(network).execute("alice"), /No Penpot sessions/);
});

test("concurrent discoveries keep response channels and users separate", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    const requester = new Instance(network);
    await owner.connect("alice", "alice-tab");
    await owner.connect("bob", "bob-tab");
    const results = await Promise.all([requester.execute("alice"), requester.execute("bob")]);
    assert.deepEqual(
        results.map((result) => result.data),
        ["alice-tab", "bob-tab"]
    );
    assert.equal(network.discoveryResponses.size, 0);
});

test("a publish completing after timeout cannot dispatch or retain its response subscription", async () => {
    const network = new RedisNetwork();
    const owner = new Instance(network);
    await owner.connect("alice", "late");
    let release!: () => void;
    network.beforeDiscoveryPublish = () =>
        new Promise<void>((resolve) => {
            release = resolve;
        });
    await assert.rejects(new Instance(network).execute("alice"), SessionDiscoveryError);
    release();
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.deepEqual(owner.executions, []);
    assert.equal(network.discoveryResponses.size, 0);
});
