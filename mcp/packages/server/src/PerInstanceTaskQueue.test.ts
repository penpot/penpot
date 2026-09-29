import assert from "node:assert/strict";
import test from "node:test";
import { PerInstanceTaskQueue } from "./PerInstanceTaskQueue";

function deferred() {
    let resolve!: () => void;
    const promise = new Promise<void>((res) => {
        resolve = res;
    });
    return { promise, resolve };
}

test("serializes tasks targeting the same plugin instance", async () => {
    const queue = new PerInstanceTaskQueue();
    const firstGate = deferred();
    const started: string[] = [];

    const first = queue.run("user-a:tab-a", async () => {
        started.push("first");
        await firstGate.promise;
    });
    await Promise.resolve();
    const second = queue.run("user-a:tab-a", async () => {
        started.push("second");
    });
    await Promise.resolve();

    assert.deepEqual(started, ["first"]);
    firstGate.resolve();
    await Promise.all([first, second]);
    assert.deepEqual(started, ["first", "second"]);
});

test("allows tasks for different plugin instances to overlap", async () => {
    const queue = new PerInstanceTaskQueue();
    const firstStarted = deferred();
    const secondStarted = deferred();
    const release = deferred();

    const first = queue.run("user-a:tab-a", async () => {
        firstStarted.resolve();
        await release.promise;
    });
    const second = queue.run("user-a:tab-b", async () => {
        secondStarted.resolve();
        await release.promise;
    });

    await Promise.all([firstStarted.promise, secondStarted.promise]);
    release.resolve();
    await Promise.all([first, second]);
});

test("serializes tasks from different tabs targeting the same Penpot file", async () => {
    const queue = new PerInstanceTaskQueue();
    const firstGate = deferred();
    const started: string[] = [];

    const first = queue.run("user-a:file-1", async () => {
        started.push("tab-a");
        await firstGate.promise;
    });
    await Promise.resolve();
    const second = queue.run("user-a:file-1", async () => {
        started.push("tab-b");
    });
    await Promise.resolve();

    assert.deepEqual(started, ["tab-a"]);
    firstGate.resolve();
    await Promise.all([first, second]);
    assert.deepEqual(started, ["tab-a", "tab-b"]);
});

test("allows edits to different Penpot files to overlap", async () => {
    const queue = new PerInstanceTaskQueue();
    const firstStarted = deferred();
    const secondStarted = deferred();
    const release = deferred();

    const first = queue.run("user-a:file-1", async () => {
        firstStarted.resolve();
        await release.promise;
    });
    const second = queue.run("user-a:file-2", async () => {
        secondStarted.resolve();
        await release.promise;
    });

    await Promise.all([firstStarted.promise, secondStarted.promise]);
    release.resolve();
    await Promise.all([first, second]);
});

test("releases the queue when a task rejects", async () => {
    const queue = new PerInstanceTaskQueue();
    const error = new Error("task failed");

    await assert.rejects(
        queue.run("user-a:tab-a", async () => Promise.reject(error)),
        error
    );
    await assert.doesNotReject(queue.run("user-a:tab-a", async () => "completed"));
});
