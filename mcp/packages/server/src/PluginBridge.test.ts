import assert from "node:assert/strict";
import test from "node:test";
import { assertPluginResponsive, HEARTBEAT_STALE_THRESHOLD_MS, PluginBridge } from "./PluginBridge";
import { PluginInstanceRegistry } from "./PluginInstanceRegistry";

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

test("routes authenticated plugin tasks to the requested Penpot tab", () => {
    const registry = new PluginInstanceRegistry<{
        pluginInstanceId: string;
        userToken: string | null;
        fileName?: string;
        pageName?: string;
    }>();
    const first = { pluginInstanceId: "tab-a", userToken: "user-a" };
    const second = { pluginInstanceId: "tab-b", userToken: "user-a" };
    registry.register(first);
    registry.register(second);

    assert.equal(registry.resolve("user-a", "tab-b"), second);
    assert.notEqual(registry.resolve("user-a", "tab-a"), second);
});

test("keeps registration separate for identical tab IDs from different users", () => {
    const registry = new PluginInstanceRegistry<{
        pluginInstanceId: string;
        userToken: string | null;
        fileName?: string;
        pageName?: string;
    }>();
    const aliceTab = { pluginInstanceId: "tab-a", userToken: "alice" };
    const bobTab = { pluginInstanceId: "tab-a", userToken: "bob" };
    registry.register(aliceTab);
    registry.register(bobTab);

    assert.equal(registry.resolve("alice", "tab-a"), aliceTab);
    assert.equal(registry.resolve("bob", "tab-a"), bobTab);
});

test("does not allow an MCP caller to route to another user's tab", () => {
    const registry = new PluginInstanceRegistry<{ pluginInstanceId: string; userToken: string | null }>();
    registry.register({ pluginInstanceId: "tab-a", userToken: "user-a" });

    assert.throws(() => registry.resolve("user-b", "tab-a"), /not connected for this user/);
    assert.equal(PluginBridge.MULTIUSER_CONNECTION_ERROR_MESSAGE.includes("user token"), true);
});
