import assert from "node:assert/strict";
import test from "node:test";
import { LocalStorageRollbackStore, RollbackLog, type RollbackStore } from "./RollbackLog.ts";

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

test("puts returned rollbacks back ahead of the ones recorded since", () => {
    const log = new RollbackLog();
    log.record(rollback("a"));
    const sent = log.pending();
    log.acknowledge(sent.length);
    log.record(rollback("b"));

    log.restore(sent);

    assert.deepEqual(log.pending(), [rollback("a"), rollback("b")]);
});

/**
 * Rollback store that keeps its events in memory, standing in for the storage that outlives a reload.
 */
class MemoryRollbackStore implements RollbackStore {
    events: unknown = [];

    load() {
        return this.events as any;
    }

    save(events: any) {
        this.events = JSON.parse(JSON.stringify(events));
    }
}

/**
 * Plugin local storage held in a map.
 */
function memoryStorage(): {
    items: Map<string, string>;
    getItem(key: string): string;
    setItem(key: string, value: unknown): void;
    removeItem(key: string): void;
} {
    const items = new Map<string, string>();
    return {
        items,
        getItem: (key) => items.get(key) ?? (null as any),
        setItem: (key, value) => void items.set(key, String(value)),
        removeItem: (key) => void items.delete(key),
    };
}

test("keeps unsent rollbacks across a reload", () => {
    const store = new MemoryRollbackStore();
    new RollbackLog(store).record(rollback("a"));

    const reloaded = new RollbackLog(store);

    assert.deepEqual(reloaded.pending(), [rollback("a")]);
});

test("forgets the stored rollbacks once a response carried them", () => {
    const store = new MemoryRollbackStore();
    const log = new RollbackLog(store);
    log.record(rollback("a"));
    log.record(rollback("b"));

    log.acknowledge(1);

    assert.deepEqual(new RollbackLog(store).pending(), [rollback("b")]);
    log.acknowledge(1);
    assert.deepEqual(new RollbackLog(store).pending(), []);
});

test("stores the rollbacks the server returns", () => {
    const store = new MemoryRollbackStore();
    new RollbackLog(store).restore([rollback("a")]);

    assert.deepEqual(new RollbackLog(store).pending(), [rollback("a")]);
});

test("keeps rollbacks in memory when the storage fails", () => {
    const failing: RollbackStore = {
        load: () => {
            throw new Error("storage unavailable");
        },
        save: () => {
            throw new Error("storage unavailable");
        },
    };
    const log = new RollbackLog(failing);

    assert.doesNotThrow(() => log.record(rollback("a")));
    assert.deepEqual(log.pending(), [rollback("a")]);
});

test("stores rollbacks per file in the plugin local storage", () => {
    const storage = memoryStorage();
    const fileA = new LocalStorageRollbackStore(storage, "file-a");
    const fileB = new LocalStorageRollbackStore(storage, "file-b");

    fileA.save([rollback("a")]);

    assert.deepEqual(new LocalStorageRollbackStore(storage, "file-a").load(), [rollback("a")]);
    assert.deepEqual(fileB.load(), []);
});

test("removes the entry of a file with no rollbacks left", () => {
    const storage = memoryStorage();
    const store = new LocalStorageRollbackStore(storage, "file-a");
    store.save([rollback("a")]);

    store.save([]);

    assert.equal(storage.items.size, 0);
});

test("ignores a stored entry that is not a rollback list", () => {
    const storage = memoryStorage();
    const store = new LocalStorageRollbackStore(storage, "file-a");

    for (const raw of ["not json", '{"fileIds": []}', '[{"fileIds": "x"}]']) {
        storage.items.clear();
        store.save([rollback("a")]);
        storage.items.set([...storage.items.keys()][0], raw);
        assert.deepEqual(store.load(), [], raw);
    }
});
