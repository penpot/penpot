import assert from "node:assert/strict";
import test from "node:test";
import { RollbackLog } from "./RollbackLog.ts";

const rollback = (id: string) => ({ fileIds: [`file-${id}`], pageIds: [`page-${id}`], shapeIds: [`shape-${id}`] });

/**
 * Installs a `penpot` global whose `on` hands each subscription to `on`.
 */
function installPenpot(on: (type: string, callback: any) => void): void {
    (globalThis as any).penpot = { on };
}

test("records the rollbacks Penpot reports", () => {
    let callback: any;
    installPenpot((type, cb) => {
        assert.equal(type, "contentrollback");
        callback = cb;
    });
    const log = new RollbackLog();
    log.listen();

    callback(rollback("a"));

    assert.deepEqual(log.pending(), [rollback("a")]);
});

test("stays empty when Penpot has no rollback event", () => {
    installPenpot(() => {
        throw new Error("Invalid enum value");
    });
    const log = new RollbackLog();

    assert.doesNotThrow(() => log.listen());
    assert.deepEqual(log.pending(), []);
});

test("keeps the rollbacks recorded after the acknowledged ones", () => {
    const log = new RollbackLog();
    log.record(rollback("a"));
    const sent = log.pending();
    log.record(rollback("b"));

    log.acknowledge(sent.length);

    assert.deepEqual(log.pending(), [rollback("b")]);
});
