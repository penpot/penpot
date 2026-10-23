import { strict as assert } from "node:assert";
import { test } from "node:test";
import { makeProgram } from "../src/composable-tests/random/program.ts";
import { PenpotSync } from "../src/composable-tests/util/PenpotSync.ts";

test("random programs replay a seed exactly", () => {
    assert.deepEqual(makeProgram(712, 40), makeProgram(712, 40));
    assert.notDeepEqual(makeProgram(712, 40), makeProgram(713, 40));
    assert.equal(makeProgram(712, 40).length, 40);
    assert.equal(makeProgram(0, 0).length, 0);
});

test("propagation fails when pending work never settles", async () => {
    const scope = globalThis as unknown as { penpot: unknown };
    const previous = scope.penpot;
    scope.penpot = { waitForLayoutUpdate: () => new Promise(() => {}) };
    try {
        await assert.rejects(PenpotSync.awaitPropagation(5), /did not settle/);
    } finally {
        scope.penpot = previous;
    }
});

test("propagation waits for the completion promise", async () => {
    const scope = globalThis as unknown as { penpot: unknown };
    const previous = scope.penpot;
    let complete!: () => void;
    scope.penpot = { waitForLayoutUpdate: () => new Promise<void>((resolve) => (complete = resolve)) };
    try {
        let settled = false;
        const waiting = PenpotSync.awaitPropagation(1000).then(() => (settled = true));
        await Promise.resolve();
        assert.equal(settled, false);
        complete();
        await waiting;
        assert.equal(settled, true);
    } finally {
        scope.penpot = previous;
    }
});
