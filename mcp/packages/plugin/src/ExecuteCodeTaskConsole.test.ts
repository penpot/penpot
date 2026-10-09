import assert from "node:assert/strict";
import test from "node:test";
import { ExecuteCodeTaskConsole } from "./task-handlers/ExecuteCodeTaskHandler.ts";

test("formats plain objects and primitives", () => {
    const console = new ExecuteCodeTaskConsole();
    console.log({ a: 1 }, null, "text", 42, undefined);
    assert.equal(console.getLog(), '[LOG] {\n  "a": 1\n} null text 42 undefined\n');
});

test("keeps the name and message of a logged error", () => {
    const console = new ExecuteCodeTaskConsole();
    console.error("failed", new TypeError("shape is undefined"));
    assert.equal(console.getLog(), "[ERROR] failed TypeError: shape is undefined\n");
});

test("does not throw for values JSON cannot serialize", () => {
    const console = new ExecuteCodeTaskConsole();
    const circular: { self?: unknown } = {};
    circular.self = circular;
    console.log(circular, { big: 1n });
    assert.equal(console.getLog(), "[LOG] [object Object] [object Object]\n");
});
