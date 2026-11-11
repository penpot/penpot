import assert from "node:assert/strict";
import test from "node:test";
import { RollbackNotices } from "./RollbackNotices";

const rollback = (id: string, shapes: string[] = [`shape-${id}`]) => ({
    fileIds: ["file-1"],
    pageIds: [`page-${id}`],
    shapeIds: shapes,
});

test("collects only the rollbacks recorded while its function runs", async () => {
    const notices = new RollbackNotices();
    notices.record([rollback("outside")]);

    const [value, rollbacks] = await notices.collect(async () => {
        notices.record([rollback("a")]);
        await Promise.resolve();
        notices.record([rollback("b")]);
        return 42;
    });

    assert.equal(value, 42);
    assert.deepEqual(rollbacks, [rollback("a"), rollback("b")]);
});

test("keeps the rollbacks of overlapping executions apart", async () => {
    const notices = new RollbackNotices();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));

    const first = notices.collect(async () => {
        await gate;
        notices.record([rollback("a")]);
    });
    const second = notices.collect(async () => {
        notices.record([rollback("b")]);
        release();
    });

    const [[, a], [, b]] = await Promise.all([first, second]);
    assert.deepEqual(a, [rollback("a")]);
    assert.deepEqual(b, [rollback("b")]);
});

test("names each affected file, page and shape once", () => {
    const text = RollbackNotices.describe([rollback("a", ["s1", "s2"]), rollback("b", ["s2"])]);

    assert.match(text, /\(2 times\)/);
    assert.match(text, /Files: file-1\n/);
    assert.match(text, /Pages: page-a, page-b\n/);
    assert.match(text, /Shapes: s1, s2$/);
});

test("lists at most 50 shapes", () => {
    const shapes = Array.from({ length: 53 }, (_, i) => `s${i}`);

    const text = RollbackNotices.describe([rollback("a", shapes)]);

    assert.match(text, /s49 \(and 3 more\)$/);
    assert.doesNotMatch(text, /s50/);
});
