import assert from "node:assert/strict";
import { once } from "node:events";
import { afterEach, test } from "node:test";
import { WebSocket } from "ws";
import { DEFAULT_TASK_QUEUE_LIMITS, PluginBridge, readTaskQueueLimits, type TaskQueueLimits } from "./PluginBridge";
import { PluginTask } from "./PluginTask";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import { RequestGuard } from "./RequestGuard";

let port = 17_900;
let bridge: PluginBridge | undefined;
let signal: AbortSignal | undefined;
const sockets: WebSocket[] = [];

afterEach(async () => {
    for (const socket of sockets.splice(0)) {
        socket.terminate();
    }
    await bridge?.close();
    bridge = undefined;
    signal = undefined;
});

/**
 * Starts a multi-user bridge with the given task timeout and queue limits.
 */
function startBridge(taskTimeoutSecs = 5, queueLimits: TaskQueueLimits = DEFAULT_TASK_QUEUE_LIMITS): PluginBridge {
    bridge = new PluginBridge(
        {
            host: "127.0.0.1",
            isMultiUserMode: () => true,
            getSessionContext: () => ({ userToken: "alice", signal }),
            requestGuard: RequestGuard.allowing(["127.0.0.1"]),
        } as PenpotMcpServer,
        ++port,
        taskTimeoutSecs,
        undefined,
        queueLimits
    );
    return bridge;
}

/**
 * A synthetic plugin which records the tasks it receives and answers them only when asked to.
 */
interface HeldPlugin {
    socket: WebSocket;
    /** IDs of the received tasks, in arrival order. */
    received: string[];
    /** resolves once the plugin has received the given number of tasks. */
    receivedCount(count: number): Promise<void>;
    /** answers a received task successfully with its ID as data. */
    answer(taskId: string, data?: unknown): void;
}

async function connect(sessionId: string): Promise<HeldPlugin> {
    const socket = new WebSocket(`ws://127.0.0.1:${port}?userToken=alice`);
    sockets.push(socket);
    await once(socket, "open");
    const received: string[] = [];
    const waiters: Array<() => void> = [];
    socket.on("message", (raw) => {
        const message = JSON.parse(raw.toString());
        if (message.id) {
            received.push(message.id);
            waiters.splice(0).forEach((wake) => wake());
        }
    });
    const initialized = once(socket, "message", { signal: AbortSignal.timeout(1500) });
    socket.send(JSON.stringify({ type: "initialize", session: { sessionId, fileId: "file", fileName: "Design" } }));
    await initialized;
    return {
        socket,
        received,
        receivedCount: async (count) => {
            while (received.length < count) {
                await new Promise<void>((resolve) => waiters.push(resolve));
            }
        },
        answer: (taskId, data = taskId) => socket.send(JSON.stringify({ id: taskId, success: true, data })),
    };
}

/**
 * Lets pending socket messages arrive, to show that a task was not sent.
 */
function settle(): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, 100));
}

/**
 * Asserts that the bridge no longer tracks any running or queued task.
 */
function assertNoTrackedTasks(target: PluginBridge): void {
    const internals = target as any;
    assert.equal(internals.pendingTasks.size, 0);
    assert.equal(internals.taskTimeouts.size, 0);
    assert.equal(internals.taskConnections.size, 0);
    assert.equal(internals.runningTasks.size, 0);
    assert.equal(internals.queuedTasks.size, 0);
}

test("a session runs its tasks one at a time in arrival order", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const tasks = [1, 2, 3].map(() => new PluginTask("test", {}));
    const results = tasks.map((task) => bridge.executePluginTask(task, "tab"));
    for (const [index, task] of tasks.entries()) {
        await plugin.receivedCount(index + 1);
        await settle();
        assert.deepEqual(
            plugin.received,
            tasks.slice(0, index + 1).map((sent) => sent.id)
        );
        plugin.answer(task.id);
    }
    assert.deepEqual(
        (await Promise.all(results)).map((result) => result.data),
        tasks.map((task) => task.id)
    );
    assertNoTrackedTasks(bridge);
});

test("tasks for different sessions run in parallel", async () => {
    const bridge = startBridge();
    const first = await connect("first");
    const second = await connect("second");
    const firstTask = new PluginTask("test", {});
    const secondTask = new PluginTask("test", {});
    const results = [bridge.executePluginTask(firstTask, "first"), bridge.executePluginTask(secondTask, "second")];
    await Promise.all([first.receivedCount(1), second.receivedCount(1)]);
    second.answer(secondTask.id);
    first.answer(firstTask.id);
    assert.deepEqual(
        (await Promise.all(results)).map((result) => result.data),
        [firstTask.id, secondTask.id]
    );
});

test("a task is refused without being sent when the session's queue is full", async () => {
    const bridge = startBridge(5, { maxQueuedTasks: 1, queueTimeoutSecs: 5 });
    const plugin = await connect("tab");
    const running = new PluginTask("test", {});
    const queued = new PluginTask("test", {});
    const results = [bridge.executePluginTask(running, "tab"), bridge.executePluginTask(queued, "tab")];
    await plugin.receivedCount(1);
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "tab"), /busy.*was not sent/);
    plugin.answer(running.id);
    await plugin.receivedCount(2);
    plugin.answer(queued.id);
    await Promise.all(results);
    assert.deepEqual(plugin.received, [running.id, queued.id]);
});

test("a queue limit of zero refuses tasks for a busy session", async () => {
    const bridge = startBridge(5, { maxQueuedTasks: 0, queueTimeoutSecs: 5 });
    const plugin = await connect("tab");
    const running = new PluginTask("test", {});
    const result = bridge.executePluginTask(running, "tab");
    await plugin.receivedCount(1);
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "tab"), /busy.*was not sent/);
    plugin.answer(running.id);
    await result;
});

test("a task waiting longer than the queue timeout fails without being sent", async () => {
    const bridge = startBridge(5, { maxQueuedTasks: 5, queueTimeoutSecs: 0.2 });
    const plugin = await connect("tab");
    const running = new PluginTask("test", {});
    const result = bridge.executePluginTask(running, "tab");
    await plugin.receivedCount(1);
    await assert.rejects(
        bridge.executePluginTask(new PluginTask("test", {}), "tab"),
        /still running an earlier task after 0.2 seconds. The task was not sent/
    );
    plugin.answer(running.id);
    assert.equal((await result).data, running.id);
    await settle();
    assert.deepEqual(plugin.received, [running.id]);
    assertNoTrackedTasks(bridge);
});

test("a timed-out task keeps its session busy until the plugin answers it", async () => {
    const bridge = startBridge(0.2);
    const plugin = await connect("tab");
    const slow = new PluginTask("test", {});
    const next = new PluginTask("test", {});
    const slowResult = bridge.executePluginTask(slow, "tab");
    await plugin.receivedCount(1);
    await assert.rejects(slowResult, /timed out after 0.2 seconds; it may still be running in Penpot/);
    const nextResult = bridge.executePluginTask(next, "tab");
    await settle();
    assert.deepEqual(plugin.received, [slow.id]);
    // the late response releases the session for the queued task
    plugin.answer(slow.id);
    await plugin.receivedCount(2);
    plugin.answer(next.id);
    assert.equal((await nextResult).data, next.id);
    assertNoTrackedTasks(bridge);
});

test("a closing connection fails its queued tasks as not run", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const running = bridge.executePluginTask(new PluginTask("test", {}), "tab");
    const queued = bridge.executePluginTask(new PluginTask("test", {}), "tab");
    await plugin.receivedCount(1);
    plugin.socket.terminate();
    await assert.rejects(running, /connection closed before the task finished/);
    await assert.rejects(queued, /closed before the task was sent; the task did not run/);
    assert.equal(plugin.received.length, 1);
    assertNoTrackedTasks(bridge);
});

test("a response from another session's plugin neither settles a task nor releases its session", async () => {
    const bridge = startBridge();
    const owner = await connect("owner");
    const other = await connect("other");
    const running = new PluginTask("test", {});
    const queued = new PluginTask("test", {});
    const results = [bridge.executePluginTask(running, "owner"), bridge.executePluginTask(queued, "owner")];
    await owner.receivedCount(1);
    other.answer(running.id, "forged");
    await settle();
    assert.deepEqual(owner.received, [running.id]);
    owner.answer(running.id);
    await owner.receivedCount(2);
    owner.answer(queued.id);
    assert.deepEqual(
        (await Promise.all(results)).map((result) => result.data),
        [running.id, queued.id]
    );
});

test("cancelling a queued tool call removes its task without sending it", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const running = new PluginTask("test", {});
    const runningResult = bridge.executePluginTask(running, "tab");
    await plugin.receivedCount(1);
    const controller = new AbortController();
    signal = controller.signal;
    const cancelled = new PluginTask("test", {});
    const cancelledResult = bridge.executePluginTask(cancelled, "tab");
    signal = undefined;
    const next = new PluginTask("test", {});
    const nextResult = bridge.executePluginTask(next, "tab");
    controller.abort();
    await assert.rejects(cancelledResult, /cancelled before the task was sent to Penpot; the task did not run/);
    plugin.answer(running.id);
    await plugin.receivedCount(2);
    plugin.answer(next.id);
    await Promise.all([runningResult, nextResult]);
    assert.deepEqual(plugin.received, [running.id, next.id]);
    assertNoTrackedTasks(bridge);
});

test("cancelling a tool call whose task already runs waits for the plugin's answer", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const controller = new AbortController();
    signal = controller.signal;
    const running = new PluginTask("test", {});
    const result = bridge.executePluginTask(running, "tab");
    await plugin.receivedCount(1);
    controller.abort();
    await settle();
    plugin.answer(running.id);
    assert.equal((await result).data, running.id);
});

test("a tool call cancelled before dispatch sends nothing", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const controller = new AbortController();
    controller.abort();
    signal = controller.signal;
    await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "tab"), /cancelled before the task/);
    await settle();
    assert.deepEqual(plugin.received, []);
});

test("shutdown fails queued tasks as not sent and running tasks as unknown", async () => {
    const bridge = startBridge();
    const plugin = await connect("tab");
    const running = bridge.executePluginTask(new PluginTask("test", {}), "tab");
    const queued = bridge.executePluginTask(new PluginTask("test", {}), "tab");
    await plugin.receivedCount(1);
    await bridge.close();
    await assert.rejects(running, /shutting down; task outcome may be unknown/);
    await assert.rejects(queued, /shutting down; the task was not sent/);
    assert.equal(plugin.received.length, 1);
    assertNoTrackedTasks(bridge);
});

test("queue limits default when unset and reject invalid values", () => {
    assert.deepEqual(readTaskQueueLimits({}), DEFAULT_TASK_QUEUE_LIMITS);
    assert.deepEqual(readTaskQueueLimits({ PENPOT_MCP_MAX_QUEUED_TASKS: "0", PENPOT_MCP_QUEUE_TIMEOUT_S: " 30 " }), {
        maxQueuedTasks: 0,
        queueTimeoutSecs: 30,
    });
    for (const value of ["-1", "1.5", "ten", "1e3"]) {
        assert.throws(() => readTaskQueueLimits({ PENPOT_MCP_MAX_QUEUED_TASKS: value }), /PENPOT_MCP_MAX_QUEUED_TASKS/);
    }
    assert.throws(() => readTaskQueueLimits({ PENPOT_MCP_QUEUE_TIMEOUT_S: "0" }), /at least 1/);
});
