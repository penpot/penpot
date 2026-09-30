import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { RedisBridge } from "./RedisBridge";

// These tests need a Redis server; they are skipped unless REDIS_URI is set
// (e.g. REDIS_URI=redis://localhost:6379 pnpm run test).
const redisUri = process.env.REDIS_URI;
const skip = redisUri ? false : "REDIS_URI is not set";

test("delivers a task request to every instance subscribed to the same token", { skip }, async () => {
    const tenant = `test-${randomUUID()}`;
    const first = new RedisBridge(redisUri!, tenant);
    const second = new RedisBridge(redisUri!, tenant);
    const issuer = new RedisBridge(redisUri!, tenant);
    try {
        const token = randomUUID();
        await first.subscribeToTasks(token, () => {});
        await second.subscribeToTasks(token, () => {});

        const receivers = await issuer.sendTaskRequest(
            token,
            { id: randomUUID(), task: "executeCode", params: {} },
            () => {}
        );

        // both instances receive the request, which is why dispatch must be claimed
        assert.equal(receivers, 2);
    } finally {
        await Promise.all([first.close(), second.close(), issuer.close()]);
    }
});

test("grants the claim for a task to exactly one instance", { skip }, async () => {
    const tenant = `test-${randomUUID()}`;
    const first = new RedisBridge(redisUri!, tenant);
    const second = new RedisBridge(redisUri!, tenant);
    try {
        const taskId = randomUUID();
        const results = await Promise.all([first.claimTask(taskId, 5_000), second.claimTask(taskId, 5_000)]);

        assert.deepEqual(results.filter(Boolean), [true]);
        assert.equal(await first.claimTask(randomUUID(), 5_000), true);
    } finally {
        await Promise.all([first.close(), second.close()]);
    }
});
