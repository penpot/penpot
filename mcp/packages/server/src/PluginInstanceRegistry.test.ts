import assert from "node:assert/strict";
import test from "node:test";
import { PluginInstanceRegistry } from "./PluginInstanceRegistry";

interface TestInstance {
    pluginInstanceId: string;
    userToken: string | null;
    label: string;
}

test("registers multiple plugin instances for the same user", () => {
    const registry = new PluginInstanceRegistry<TestInstance>();
    const first = { pluginInstanceId: "tab-a", userToken: "user-a", label: "A" };
    const second = { pluginInstanceId: "tab-b", userToken: "user-a", label: "B" };

    registry.register(first);
    registry.register(second);

    assert.deepEqual(registry.list("user-a"), [first, second]);
    assert.equal(registry.resolve("user-a", "tab-b"), second);
});

test("replaces a reconnecting instance and ignores cleanup from its stale socket", () => {
    const registry = new PluginInstanceRegistry<TestInstance>();
    const stale = { pluginInstanceId: "tab-a", userToken: "user-a", label: "stale" };
    const current = { pluginInstanceId: "tab-a", userToken: "user-a", label: "current" };

    assert.equal(registry.register(stale), undefined);
    assert.equal(registry.register(current), stale);

    assert.equal(registry.unregister(stale), false);
    assert.deepEqual(registry.list("user-a"), [current]);
});

test("does not expose another user's plugin instances", () => {
    const registry = new PluginInstanceRegistry<TestInstance>();
    const alice = { pluginInstanceId: "alice-tab", userToken: "alice", label: "Alice" };
    const bob = { pluginInstanceId: "bob-tab", userToken: "bob", label: "Bob" };

    registry.register(alice);
    registry.register(bob);

    assert.deepEqual(registry.list("alice"), [alice]);
    assert.throws(() => registry.resolve("alice", "bob-tab"), /not connected for this user/);
});

test("requires an explicit target when a user has multiple connected tabs", () => {
    const registry = new PluginInstanceRegistry<TestInstance>();
    registry.register({ pluginInstanceId: "tab-a", userToken: "user-a", label: "A" });
    registry.register({ pluginInstanceId: "tab-b", userToken: "user-a", label: "B" });

    assert.throws(() => registry.resolve("user-a"), /Pass pluginInstanceId/);
});

test("removes a disconnected instance without removing its user's other tabs", () => {
    const registry = new PluginInstanceRegistry<TestInstance>();
    const first = { pluginInstanceId: "tab-a", userToken: "user-a", label: "A" };
    const second = { pluginInstanceId: "tab-b", userToken: "user-a", label: "B" };
    registry.register(first);
    registry.register(second);

    assert.equal(registry.unregister(first), true);
    assert.deepEqual(registry.list("user-a"), [second]);
});
