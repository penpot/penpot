import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { BLOCK_CHARS, capEval, cleanOutput, EVAL_CHARS, renderTail, shapeRun, tail } from "./output";

test("cleanOutput drops colour codes and trailing blank lines", () => {
    assert.equal(cleanOutput("\x1b[32m6 tests, 0 failures\x1b[0m\r\nok\n\n\n"), "6 tests, 0 failures\nok");
});

describe("tail", () => {
    const lines = (n: number) => Array.from({ length: n }, (_, k) => `line ${k + 1}`).join("\n");

    test("keeps the last lines and says how many were cut", () => {
        const t = tail(lines(250), 200);
        assert.equal(t.cutLines, 50);
        assert.deepEqual(renderTail(t).split("\n").slice(0, 2), ["[50 earlier lines cut]", "line 51"]);
    });

    test("one line over the char budget keeps its end", () => {
        const t = tail(`first\n${"x".repeat(50)}y`, 300, 10);
        assert.deepEqual(t, { text: `${"x".repeat(9)}y`, cutLines: 1, cutChars: 41 });
    });
});

describe("shapeRun on a long kaocha run", () => {
    // A whole-suite run as kaocha 1.91 prints it through cli-run: one dots line (one char per assertion, groups in
    // brackets), the seed, the failure blocks, the totals, then cli-run's verdict.
    const failBlock = (k: number) =>
        [
            "",
            `FAIL in backend-tests.f${k}-test/case-${k} (f${k}_test.clj:${10 + k})`,
            "expected: (= 1 (count rows))",
            `  actual: (not (= 1 ${k}))`,
            "ex-data ".repeat(60),
        ].join("\n");
    const dots = `[(${".".repeat(4000)})(${".".repeat(3689)}F)(${"F".repeat(29)}E)]`;
    const suite = [
        dots,
        "Randomized with --seed 309291445",
        ...Array.from({ length: 30 }, (_, k) => failBlock(k)),
        "",
        `ERROR in backend-tests.huge-test/dump (huge_test.clj:1)\n${"y".repeat(5000)}`,
        "1610 tests, 7720 assertions, 1 errors, 30 failures.",
        "[testjvm] 1610 tests, 7689 assertions passed, 30 failed, 1 errors; reloaded 2 namespaces; 64.2 s; seed 309291445",
    ].join("\n");

    test("verdict and seed first, the dots as one count, then the first failures within budget", () => {
        const shaped = shapeRun(suite);
        const lines = shaped.split("\n");
        assert.deepEqual(lines.slice(0, 4), [
            "[testjvm] 1610 tests, 7689 assertions passed, 30 failed, 1 errors; reloaded 2 namespaces; 64.2 s; seed 309291445",
            "Randomized with --seed 309291445",
            "[kaocha dots: 7689 pass, 30 fail, 1 error]",
            "1610 tests, 7720 assertions, 1 errors, 30 failures.",
        ]);
        assert.ok(!shaped.includes("...."));
        assert.equal(lines[5], "FAIL in backend-tests.f0-test/case-0 (f0_test.clj:10)");
        const kept = lines.filter((l) => /^(FAIL|ERROR) in /.test(l)).length;
        assert.ok(kept > 5 && kept < 31, `kept ${kept} blocks`);
        const last = lines.at(-1) ?? "";
        assert.ok(
            last.startsWith(
                `[${31 - kept} more failure blocks cut: FAIL in backend-tests.f${kept}-test/case-${kept} (f${kept}_test.clj:${10 + kept}); `
            ),
            last
        );
        assert.ok(last.endsWith("; ERROR in backend-tests.huge-test/dump (huge_test.clj:1)]"), last);
        assert.ok(shaped.length < BLOCK_CHARS + 6_000, `${shaped.length} chars`);
    });

    test("a single long line is cut at 2000 chars, saying how much", () => {
        const shaped = shapeRun(
            `ERROR in (x-test)\n${"z".repeat(5000)}\n[testjvm] 1 tests, 0 assertions passed, 0 failed, 1 errors; reloaded 0 namespaces; 0.1 s; seed 1`
        );
        assert.equal(shaped.split("\n")[3], `${"z".repeat(2000)} [3000 chars cut]`);
    });

    test("dots sharing a line with test output are counted and dropped, the output kept", () => {
        const shaped = shapeRun(
            `[(${".".repeat(30)}some println output${".".repeat(25)})]\n6 tests, 55 assertions, 0 failures.`
        );
        assert.deepEqual(shaped.split("\n"), [
            "[kaocha dots: 55 pass]",
            "some println output",
            "6 tests, 55 assertions, 0 failures.",
        ]);
    });
});

describe("capEval", () => {
    test("output within the budget is untouched", () => {
        assert.equal(capEval("x".repeat(EVAL_CHARS)), "x".repeat(EVAL_CHARS));
    });

    test("long output keeps its start and end around a marker that says what to do", () => {
        const capped = capEval(`${"a".repeat(10_000)}${"b".repeat(20_000)}`).split("\n");
        assert.equal(capped[0], "a".repeat(4_000));
        assert.match(capped[1], /^\[14000 of 30000 chars cut here\. Bind the value/);
        assert.equal(capped[2], "b".repeat(12_000));
    });
});
