import assert from "node:assert/strict";
import test from "node:test";
import { ScriptRunner, truncateMiddle } from "./runScript";

const LABELS = { success: "PASS", failure: "FAIL" };

test("output within the limit passes through untouched", () => {
    const text = "1 tests, 1 assertions, 0 failures";

    assert.equal(truncateMiddle(text, ScriptRunner.MAX_OUTPUT_CHARS), text);
});

test("long output keeps its head and tail and names the omitted count", () => {
    const head = "H".repeat(15_000);
    const tail = "T".repeat(15_000);

    const result = truncateMiddle(head + tail, ScriptRunner.MAX_OUTPUT_CHARS);

    assert.equal(result.length, ScriptRunner.MAX_OUTPUT_CHARS);
    assert.ok(result.startsWith(head.slice(0, 1_000)));
    assert.ok(result.endsWith(tail.slice(-1_000)));
    assert.match(result, /10000 characters omitted/);
});

test("a failure keeps its summary tail inside the limit", () => {
    const result = new ScriptRunner("/tmp").format(
        {
            exitCode: 1,
            stdout: "x".repeat(40_000) + "\nFAIL in (some-test)\n1 tests, 0 assertions, 1 failures",
            stderr: "",
        },
        LABELS
    );

    assert.ok(result.startsWith("FAIL (exit 1)\n"));
    assert.ok(result.endsWith("1 tests, 0 assertions, 1 failures"));
    assert.ok(result.length < ScriptRunner.MAX_OUTPUT_CHARS + 20);
});

test("format puts the status first and appends stderr after stdout", () => {
    const runner = new ScriptRunner("/tmp");

    assert.equal(runner.format({ exitCode: 0, stdout: "nil", stderr: "" }, LABELS), "PASS\nnil");
    assert.equal(
        runner.format({ exitCode: 1, stdout: "", stderr: "[test-repl] boom" }, LABELS),
        "FAIL (exit 1)\n[test-repl] boom"
    );
});
