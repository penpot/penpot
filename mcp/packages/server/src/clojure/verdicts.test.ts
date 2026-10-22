import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { type CallOutcome, evalVerdict, testVerdict } from "./verdicts";

const RUN_LINE = "[testjvm] 6 tests, 12 assertions passed, 0 failed, 0 errors; reloaded 0 namespaces; 0.3 s; seed 7";

const label = (o: CallOutcome) => testVerdict(o).label;

describe("test verdicts, one per exit status and line", () => {
    const cases: [string, CallOutcome, string, boolean][] = [
        ["0", { code: 0, out: RUN_LINE }, "PASS", false],
        ["1 with a verdict line", { code: 1, out: RUN_LINE.replace("0 failed", "1 failed") }, "FAIL", false],
        ["1 no match", { code: 1, out: "[testjvm] no test matched ['x]; an id is …" }, "NO MATCH", false],
        ["1 without any line", { code: 1, out: "" }, "ERROR", true],
        [
            "2",
            { code: 2, out: "[testjvm] reload failed in app.x (backend/src/app/x.clj:3): boom" },
            "RELOAD FAILED",
            false,
        ],
        ["2 without the line", { code: 2, out: "" }, "ERROR", true],
        ["3 not running", { code: 3, out: "" }, "ERROR", true],
        ["3 died", { code: 3, out: "", died: true }, "JVM DIED", true],
        ["4 gave up waiting", { code: 4, out: "[testjvm] gave up waiting for run of a started 9 s ago" }, "BUSY", true],
        [
            "4 cancelled between tests",
            { code: 4, out: `${RUN_LINE}\n[testjvm] cancelled; 3 tests not run` },
            "CANCELLED",
            true,
        ],
        [
            "4 deadline between tests",
            { code: 4, out: `${RUN_LINE}\n[testjvm] deadline passed; 3 tests not run` },
            "TIMEOUT",
            true,
        ],
        ["4 client deadline", { code: 4, out: "", timedOut: true }, "TIMEOUT", true],
        ["5", { code: 5, out: "[testjvm] restart needed: backend/deps.edn changed" }, "ERROR", true],
        ["6", { code: 6, out: "[testjvm] environment: 3 storage directories are not writable" }, "ERROR", true],
        ["unknown status", { code: 9, out: "" }, "ERROR", true],
    ];
    for (const [name, outcome, expected, error] of cases) {
        test(name, () => {
            const verdict = testVerdict(outcome);
            assert.equal(verdict.label, expected);
            assert.equal(verdict.error, error);
        });
    }

    test("the notes carry what the line says", () => {
        assert.match(
            testVerdict({ code: 4, out: "[testjvm] cancelled; 3 tests not run" }).note!,
            /3 tests did not run/
        );
        assert.match(testVerdict({ code: 5, out: "[testjvm] restart needed: x changed" }).note!, /x changed/);
        assert.match(
            testVerdict({ code: 6, out: "noise\n[testjvm] environment: storage is root's; repair: chown" }).note!,
            /^\[testjvm\] environment: storage is root's; repair: chown$/
        );
    });

    test("a deadline line wins over the client's own deadline flag", () => {
        assert.equal(label({ code: 4, out: "[testjvm] cancelled; 1 tests not run", timedOut: true }), "CANCELLED");
    });
});

describe("eval verdicts", () => {
    test("each exit status", () => {
        assert.equal(evalVerdict({ code: 0, out: "3" }, "test").label, "OK");
        assert.equal(evalVerdict({ code: 1, out: "boom" }, "test").label, "THREW");
        assert.equal(
            evalVerdict({ code: 2, out: "[testjvm] reload failed in a (f:1): x" }, "test").label,
            "RELOAD FAILED"
        );
        assert.equal(evalVerdict({ code: 3, out: "" }, "test").label, "ERROR");
        assert.equal(evalVerdict({ code: 3, out: "", died: true }, "test").label, "JVM DIED");
        assert.equal(evalVerdict({ code: 4, out: "", timedOut: true }, "test").label, "TIMEOUT");
        assert.equal(evalVerdict({ code: 4, out: "", cancelled: true }, "test").label, "CANCELLED");
        assert.equal(evalVerdict({ code: 5, out: "[testjvm] restart needed: x" }, "test").label, "ERROR");
        assert.equal(evalVerdict({ code: 6, out: "[mcp] harness error: x" }, "test").label, "ERROR");
    });

    test("a value cut in the JVM says how to see it", () => {
        assert.match(evalVerdict({ code: 0, out: "", valueCut: true }, "test").note!, /\(def r <expr>\)/);
    });

    test("the backend's failures point at the backend, not the test JVM", () => {
        assert.match(evalVerdict({ code: 3, out: "" }, "backend").note!, /backend's nREPL/);
        assert.match(evalVerdict({ code: 3, out: "", died: true }, "backend").note!, /backend window/);
    });
});
