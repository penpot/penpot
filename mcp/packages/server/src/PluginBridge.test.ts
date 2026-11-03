import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { WebSocket } from "ws";
import type { PenpotMcpServer } from "./PenpotMcpServer";
import { assertPluginResponsive, HEARTBEAT_STALE_THRESHOLD_MS, PluginBridge } from "./PluginBridge";
import { PluginTask } from "./PluginTask";

test("passes for a responsive connection with a recent heartbeat", () => {
    const now = 1_000_000;
    assert.doesNotThrow(() => assertPluginResponsive({ frozen: false, lastHeartbeat: now - 5_000 }, now));
});

test("passes when the heartbeat age is exactly at the threshold", () => {
    const now = 1_000_000;
    const lastHeartbeat = now - HEARTBEAT_STALE_THRESHOLD_MS;
    assert.doesNotThrow(() => assertPluginResponsive({ frozen: false, lastHeartbeat }, now));
});

test("throws a frozen-specific error when the tab reported it is being frozen", () => {
    const now = 1_000_000;
    assert.throws(() => assertPluginResponsive({ frozen: true, lastHeartbeat: now }, now), /has been frozen/);
});

test("throws a suspended error when no heartbeat has arrived within the threshold", () => {
    const now = 1_000_000;
    const lastHeartbeat = now - (HEARTBEAT_STALE_THRESHOLD_MS + 1);
    assert.throws(
        () => assertPluginResponsive({ frozen: false, lastHeartbeat }, now),
        /appears to be suspended by the browser/
    );
});

test("includes the heartbeat age, in seconds, in the suspended error", () => {
    const now = 1_000_000;
    const lastHeartbeat = now - 45_000;
    assert.throws(() => assertPluginResponsive({ frozen: false, lastHeartbeat }, now), /no heartbeat for 45s/);
});

test("honours a custom stale threshold", () => {
    const now = 1_000_000;
    const lastHeartbeat = now - 2_000;

    assert.doesNotThrow(() => assertPluginResponsive({ frozen: false, lastHeartbeat }, now));
    assert.throws(
        () => assertPluginResponsive({ frozen: false, lastHeartbeat }, now, 1_000),
        /appears to be suspended by the browser/
    );
});

async function withStalePlugin(heartbeatStaleMs: number | undefined, check: (bridge: PluginBridge) => Promise<void>) {
    const mcpServer = {
        host: "127.0.0.1",
        isMultiUserMode: () => false,
        getSessionContext: () => ({ userToken: undefined }),
    } as unknown as PenpotMcpServer;
    const bridge = new PluginBridge(mcpServer, 0, 5, undefined, heartbeatStaleMs);
    const internals = bridge as any;
    await once(internals.wsServer, "listening");
    const plugin = new WebSocket(`ws://127.0.0.1:${internals.wsServer.address().port}`);
    try {
        await once(plugin, "open");
        const initialized = once(plugin, "message", { signal: AbortSignal.timeout(1500) });
        plugin.send(
            JSON.stringify({ type: "initialize", session: { sessionId: "tab", fileId: "file-1", fileName: "Design" } })
        );
        await initialized;
        plugin.on("message", (raw) => {
            const request = JSON.parse(raw.toString());
            plugin.send(JSON.stringify({ id: request.id, success: true, data: "ok" }));
        });
        const connection = [...internals.connectionsBySocket.values()][0];
        connection.lastHeartbeat = Date.now() - 60_000;
        await check(bridge);
    } finally {
        plugin.terminate();
        await bridge.close();
    }
}

test("refuses tasks for a plugin whose heartbeat is older than the default threshold", async () => {
    await withStalePlugin(undefined, async (bridge) => {
        await assert.rejects(bridge.executePluginTask(new PluginTask("test", {}), "tab"), /no heartbeat for 60s/);
    });
});

test("sends tasks to the same plugin when the threshold is configured higher", async () => {
    await withStalePlugin(600_000, async (bridge) => {
        assert.equal((await bridge.executePluginTask(new PluginTask("test", {}), "tab")).data, "ok");
    });
});
