import assert from "node:assert/strict";
import test from "node:test";
import { formatLogArgument } from "./ConsoleUtils.ts";

test("formats plain objects as indented JSON", () => {
    assert.equal(formatLogArgument({ a: 1 }), '{\n  "a": 1\n}');
    assert.equal(formatLogArgument(null), "null");
});

test("formats primitives with String()", () => {
    assert.equal(formatLogArgument("text"), "text");
    assert.equal(formatLogArgument(42), "42");
    assert.equal(formatLogArgument(undefined), "undefined");
});

test("keeps the name and message of a logged error", () => {
    assert.equal(formatLogArgument(new TypeError("shape is undefined")), "TypeError: shape is undefined");
});

test("does not throw for values JSON cannot serialize", () => {
    const circular: { self?: unknown } = {};
    circular.self = circular;

    assert.equal(formatLogArgument(circular), "[object Object]");
    assert.equal(formatLogArgument({ big: 1n }), "[object Object]");
});
