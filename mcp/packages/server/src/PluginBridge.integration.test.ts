import assert from "node:assert/strict";
import test from "node:test";
import { WebSocket } from "ws";
import { PluginBridge } from "./PluginBridge";
import { PenpotMcpServer } from "./PenpotMcpServer";
import { PluginTask } from "./PluginTask";

/**
 * A fake Penpot plugin tab: a real WebSocket client that speaks the PluginBridge
 * protocol and lets the test decide when (or whether) to answer a task.
 */
class FakeTab {
    public readonly received: Array<{ id: string; task: string }> = [];
    private readonly pending = new Map<string, any>();

    private constructor(
        public readonly ws: WebSocket,
        public readonly pluginInstanceId: string
    ) {}

    static async connect(
        port: number,
        options: {
            pluginInstanceId: string;
            userToken?: string;
            fileId?: string;
            fileName?: string;
            pageName?: string;
            autoRespond?: boolean;
        }
    ): Promise<FakeTab> {
        const query = new URLSearchParams({ pluginInstanceId: options.pluginInstanceId });
        if (options.userToken) {
            query.set("userToken", options.userToken);
        }
        const ws = new WebSocket(`ws://localhost:${port}?${query.toString()}`);
        const tab = new FakeTab(ws, options.pluginInstanceId);

        await new Promise<void>((resolve, reject) => {
            ws.once("open", () => resolve());
            ws.once("error", reject);
        });

        ws.on("message", (raw: Buffer) => {
            const message = JSON.parse(raw.toString());
            if (!message?.task) {
                return;
            }
            tab.received.push({ id: message.id, task: message.task });
            tab.pending.set(message.id, message);
            if (options.autoRespond !== false) {
                tab.respond(message.id);
            }
        });

        // Publish the tab context the way the real plugin does, so file-level
        // serialization can be exercised against a known document.
        ws.send(
            JSON.stringify({
                type: "instance-context",
                fileId: options.fileId ?? null,
                fileName: options.fileName ?? "Untitled",
                pageName: options.pageName ?? "Unknown page",
            })
        );

        return tab;
    }

    /** Answers a previously received task request. */
    respond(id: string, result: unknown = { result: "ok", log: "" }): void {
        assert.ok(this.pending.delete(id), `no pending task ${id}`);
        this.ws.send(JSON.stringify({ id, success: true, data: result }));
    }

    close(): void {
        this.ws.close();
    }
}

/** Waits until `condition` becomes true or the timeout expires. */
async function waitFor(condition: () => boolean, timeoutMs = 2000): Promise<void> {
    const deadline = Date.now() + timeoutMs;
    while (!condition()) {
        if (Date.now() > deadline) {
            throw new Error("timed out waiting for condition");
        }
        await new Promise((resolve) => setTimeout(resolve, 10));
    }
}

interface ServerFixture {
    server: PenpotMcpServer;
    bridge: PluginBridge;
    port: number;
    close: () => Promise<void>;
}

function withEnv(overrides: Record<string, string | undefined>): () => void {
    const previous = new Map<string, string | undefined>();
    for (const [key, value] of Object.entries(overrides)) {
        previous.set(key, process.env[key]);
        if (value === undefined) {
            delete process.env[key];
        } else {
            process.env[key] = value;
        }
    }
    return () => {
        for (const [key, value] of previous) {
            if (value === undefined) {
                delete process.env[key];
            } else {
                process.env[key] = value;
            }
        }
    };
}

let portCursor = 14_700;

async function startServer(multiUser: boolean): Promise<ServerFixture> {
    const port = portCursor++;
    const wsPort = portCursor++;
    const restore = withEnv({
        PENPOT_MCP_SERVER_HOST: "localhost",
        PENPOT_MCP_SERVER_PORT: String(port),
        PENPOT_MCP_WEBSOCKET_PORT: String(wsPort),
        PENPOT_MCP_REPL_PORT: String(portCursor++),
        PENPOT_MCP_REPL_HOST: undefined,
        PENPOT_MCP_DEVENV: undefined,
        PENPOT_MCP_REPL_ENABLE: undefined,
    });

    const server = new PenpotMcpServer(multiUser);
    restore();
    const bridge = server.pluginBridge;

    return {
        server,
        bridge,
        port: wsPort,
        close: async () => {
            await server.stop();
        },
    };
}

test("keeps several Penpot tabs of the same user connected at once", async () => {
    const fixture = await startServer(true);
    const tabs: FakeTab[] = [];
    try {
        tabs.push(await FakeTab.connect(fixture.port, { pluginInstanceId: "tab-a", userToken: "user-1" }));
        tabs.push(await FakeTab.connect(fixture.port, { pluginInstanceId: "tab-b", userToken: "user-1" }));

        await waitFor(() => fixture.bridge.listClientConnections("user-1").length === 2);

        const connected = fixture.bridge.listClientConnections("user-1");
        assert.equal(connected.length, 2);
        assert.deepEqual(connected.map((connection) => connection.pluginInstanceId).sort(), ["tab-a", "tab-b"]);

        // another user must never see those tabs, and a direct lookup outside
        // an HTTP session context must not fall back to someone else's tabs
        assert.equal(fixture.bridge.listClientConnections("user-2").length, 0);
        assert.throws(() => fixture.bridge.getClientConnection("tab-a"), /No userToken found in session context/);
    } finally {
        for (const tab of tabs) {
            tab.close();
        }
        await fixture.close();
    }
});

test("routes a task only to the explicitly selected tab", async () => {
    const fixture = await startServer(false);
    const tabs: FakeTab[] = [];
    try {
        tabs.push(await FakeTab.connect(fixture.port, { pluginInstanceId: "tab-a" }));
        tabs.push(await FakeTab.connect(fixture.port, { pluginInstanceId: "tab-b" }));
        await waitFor(() => fixture.bridge.listClientConnections().length === 2);

        // ambiguous target: two tabs and no selection
        await assert.rejects(
            fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "1" })),
            /Multiple \(\d+\) Penpot MCP Plugin instances are connected/
        );

        await fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "to-b" }), "tab-b");

        assert.equal(tabs[0].received.length, 0, "tab-a must not receive the task");
        assert.equal(tabs[1].received.length, 1, "tab-b must receive the task");

        await assert.rejects(
            fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "missing" }), "tab-zzz"),
            /not connected/
        );
    } finally {
        for (const tab of tabs) {
            tab.close();
        }
        await fixture.close();
    }
});

test("serializes tasks that target the same Penpot file across tabs", async () => {
    const fixture = await startServer(false);
    const tabs: FakeTab[] = [];
    try {
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-a",
                fileId: "file-1",
                fileName: "beNext",
                pageName: "A · Home",
                autoRespond: false,
            })
        );
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-b",
                fileId: "file-1",
                fileName: "beNext",
                pageName: "B · Legal",
                autoRespond: false,
            })
        );
        await waitFor(() => {
            const connections = fixture.bridge.listClientConnections();
            return connections.length === 2 && connections.every((connection) => connection.fileId === "file-1");
        });

        const first = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "first" }), "tab-a");
        const second = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "second" }), "tab-b");

        // same file: tab-b must not start while tab-a is still running
        await waitFor(() => tabs[0].received.length === 1);
        await new Promise((resolve) => setTimeout(resolve, 150));
        assert.equal(tabs[1].received.length, 0, "same-file task must wait its turn");

        tabs[0].respond(tabs[0].received[0].id);
        await first;

        await waitFor(() => tabs[1].received.length === 1);
        tabs[1].respond(tabs[1].received[0].id);
        await second;
    } finally {
        for (const tab of tabs) {
            tab.close();
        }
        await fixture.close();
    }
});

test("runs tasks for different Penpot files at the same time", async () => {
    const fixture = await startServer(false);
    const tabs: FakeTab[] = [];
    try {
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-a",
                fileId: "file-1",
                autoRespond: false,
            })
        );
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-b",
                fileId: "file-2",
                autoRespond: false,
            })
        );
        await waitFor(() => fixture.bridge.listClientConnections().length === 2);

        const first = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "file-1" }), "tab-a");
        const second = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "file-2" }), "tab-b");

        // different files must overlap: both tabs are busy at once
        await waitFor(() => tabs[0].received.length === 1 && tabs[1].received.length === 1);

        tabs[0].respond(tabs[0].received[0].id);
        tabs[1].respond(tabs[1].received[0].id);
        await Promise.all([first, second]);
    } finally {
        for (const tab of tabs) {
            tab.close();
        }
        await fixture.close();
    }
});

test("rejects a queued task when its tab switches files before dispatch", async () => {
    const fixture = await startServer(false);
    const tabs: FakeTab[] = [];
    try {
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-a",
                fileId: "file-1",
                autoRespond: false,
            })
        );
        tabs.push(
            await FakeTab.connect(fixture.port, {
                pluginInstanceId: "tab-b",
                fileId: "file-1",
                autoRespond: false,
            })
        );
        await waitFor(() => {
            const connections = fixture.bridge.listClientConnections();
            return connections.length === 2 && connections.every((connection) => connection.fileId === "file-1");
        });

        const first = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "blocker" }), "tab-a");
        const queued = fixture.bridge.executePluginTask(new PluginTask("executeCode", { code: "stale" }), "tab-b");

        await waitFor(() => tabs[0].received.length === 1);

        // the queued tab opens another document while still holding the queue
        tabs[1].ws.send(
            JSON.stringify({
                type: "instance-context",
                fileId: "file-9",
                fileName: "other",
                pageName: "page",
            })
        );
        await waitFor(
            () =>
                fixture.bridge.listClientConnections().find((c) => c.pluginInstanceId === "tab-b")?.fileId === "file-9"
        );

        tabs[0].respond(tabs[0].received[0].id);
        await first;

        await assert.rejects(queued, /switched files while the task was queued/);
        assert.equal(tabs[1].received.length, 0, "stale queued task must not be dispatched");
    } finally {
        for (const tab of tabs) {
            tab.close();
        }
        await fixture.close();
    }
});
