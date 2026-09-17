import test from "node:test";
import assert from "node:assert/strict";
import {
  readOptions,
  bounded,
  main,
  recordAttempt,
  finishCases,
  defaultFeatures,
} from "../../renderer-benchmarks.js";

test("build provenance expands the renderer's declared default features", () => {
  assert.deepEqual(defaultFeatures({ default: [] }), ["default"]);
  assert.deepEqual(
    defaultFeatures({ default: ["fast"], fast: ["shared"], shared: ["fast"] }),
    ["default", "fast", "shared"],
  );
  assert.throws(
    () => defaultFeatures({ default: ["profile"], profile: [] }),
    /diagnostic/,
  );
});
import {
  collectCases,
  deriveSeed,
  validateMetrics,
} from "../../../resources/wasm-playground/benchmarks/contract.js";
import { scenarios } from "../../../resources/wasm-playground/scenes/index.js";

test("CLI validates bounded numbers, commands and URLs before execution", () => {
  assert.equal(readOptions([]).repetitions, 10);
  for (const args of [
    ["--repetitions", "NaN"],
    ["--warmups", "-1"],
    ["--seed", "4294967296"],
    ["--base-url", "file:///tmp"],
    ["--unknown"],
    ["compare", "one.json"],
  ]) {
    assert.throws(() => readOptions(args));
  }
  assert.equal(
    readOptions(["compare", "before.json", "after.json", "--diagnostic"])
      .diagnostic,
    true,
  );
});

test("case collector retains a single scene seed across lifecycle and interaction groups", () => {
  const cases = collectCases(scenarios, 42);
  assert.equal(cases.length, 21);
  const rectangles = cases.filter((c) => c.scenarioId === "rects");
  assert.equal(new Set(rectangles.map((c) => c.sceneSeed)).size, 1);
  assert.equal(rectangles[0].sceneSeed, deriveSeed(42, "rects/default"));
  assert.throws(() => collectCases(scenarios, 42, "missing"), /No matching/);
  assert.throws(
    () => collectCases([scenarios[0], scenarios[0]], 42),
    /duplicate/,
  );
});

test("case collector rejects missing interactions and non-JSON case values", () => {
  const scenario = scenarios[0];
  const invalid = {
    ...scenario,
    cases: [{ ...scenario.cases[0], interactions: {} }],
  };
  assert.throws(() => collectCases([invalid], 42), /Incomplete/);
  assert.throws(
    () =>
      collectCases(
        [
          {
            ...scenario,
            cases: [{ ...scenario.cases[0], params: { count: Infinity } }],
          },
        ],
        42,
      ),
    /Non-serializable/,
  );
});

test("watchdog bounds unresolved work and propagates cancellation", async () => {
  await assert.rejects(bounded(new Promise(() => {}), 10), /watchdog/);
  const abort = new AbortController();
  const operation = bounded(new Promise(() => {}), 10000, abort.signal);
  abort.abort(new Error("stopped"));
  await assert.rejects(operation, /stopped/);
  assert.equal(await bounded(Promise.resolve(42), 100), 42);
});

test("invalid aggregates retain an explicit reason", () => {
  assert.deepEqual(
    validateMetrics({ uploadMs: 0, timeToFullMs: NaN, activeInteractionMs: 2 }),
    {
      uploadMs: "below-timer-resolution",
      timeToFullMs: "non-finite",
    },
  );
});

test("help does not need a renderer build or browser", async () => {
  assert.equal(await main(["--help"]), 0);
});

test("changed environment is stored as a failed observation", () => {
  const c = {
    status: "running",
    attempts: [],
    environment: { webgl: { renderer: "A" } },
  };
  recordAttempt(
    c,
    {
      status: "ok",
      metrics: { uploadMs: 1 },
      environment: { webgl: { renderer: "B" } },
    },
    0,
    0,
  );
  assert.equal(c.attempts[0].status, "failed");
  assert.match(c.attempts[0].error, /environment changed/);
  assert.equal(c.status, "failed");
});

test("fatal failure and interruption preserve partial and unattempted counts", () => {
  for (const status of ["failed", "interrupted"]) {
    const cases = [
      { status: "complete", attempts: [{ status: "ok" }, { status: "ok" }] },
      { status: "running", attempts: [{ status: "ok" }] },
      { status: "unattempted", attempts: [] },
    ];
    finishCases(cases, 2, status);
    assert.deepEqual(
      cases.map((c) => [c.status, c.unattempted]),
      [
        ["complete", 0],
        [status, 1],
        ["unattempted", 2],
      ],
    );
  }
});
