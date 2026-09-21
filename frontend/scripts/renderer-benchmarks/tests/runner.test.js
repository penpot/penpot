import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  readOptions,
  bounded,
  main,
  recordAttempt,
  finishCases,
  defaultFeatures,
  planFeatureMatrix,
  validateFeatureSelection,
  recordAmbientEnv,
  BUILD_ENV,
  classifyConfigResult,
  ab,
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

test("feature matrix plans Gray-ordered configurations and lattice edges", () => {
  assert.deepEqual(planFeatureMatrix(["branch-b"]), {
    configurations: [
      { slug: "0", features: [] },
      { slug: "1", features: ["branch-b"] },
    ],
    edges: [{ from: "0", to: "1", toggledFeature: "branch-b" }],
  });
  const two = planFeatureMatrix(["a", "b"]);
  assert.deepEqual(
    two.configurations.map((c) => c.slug),
    ["00", "01", "11", "10"],
  );
  assert.deepEqual(
    two.configurations.map((c) => c.features),
    [[], ["b"], ["a", "b"], ["a"]],
  );
  assert.deepEqual(
    two.edges.map((e) => `${e.from}__${e.to}:${e.toggledFeature}`).sort(),
    ["00__01:b", "00__10:a", "01__11:a", "10__11:b"],
  );
  const three = planFeatureMatrix(["a", "b", "c"]);
  assert.equal(three.configurations.length, 8);
  assert.equal(three.edges.length, 12);
  assert.equal(new Set(three.edges.map((e) => `${e.from}__${e.to}`)).size, 12);
  for (const config of three.configurations) {
    assert.equal(
      three.edges.filter((e) => e.from === config.slug || e.to === config.slug)
        .length,
      3,
    );
  }
});

test("ab help needs no features and dry run is ab-only", async () => {
  assert.equal(readOptions(["ab", "--help"]).command, "ab");
  assert.equal(await main(["ab", "--help"]), 0);
  for (const args of [
    ["run", "--dry-run"],
    ["compare", "a.json", "b.json", "--dry-run"],
  ]) {
    assert.throws(() => readOptions(args), /dry-run/);
  }
});

test("CLI parses feature selection for run and ab", () => {
  assert.deepEqual(readOptions(["run"]).features, []);
  assert.deepEqual(readOptions(["run", "--features", "b,a"]).features, [
    "a",
    "b",
  ]);
  assert.deepEqual(readOptions(["run", "--features", "a,a,, b "]).features, [
    "a",
    "b",
  ]);
  assert.deepEqual(readOptions(["run", "--features", "profile"]).features, [
    "profile",
  ]);
  const ab = readOptions(["ab", "--features", "branch-b"]);
  assert.deepEqual(ab.features, ["branch-b"]);
  assert.equal(ab.maxFeatures, 3);
  assert.equal(ab.confirm, false);
  assert.equal(ab.dryRun, false);
  assert.equal(
    readOptions(["ab", "--features", "a", "--confirm", "--dry-run"]).confirm,
    true,
  );
  for (const args of [
    ["ab"],
    ["ab", "--features", ""],
    ["ab", "--features", "a", "--max-features", "0"],
    ["ab", "--features", "a", "--max-features", "7"],
    ["ab", "--features", "Bad_Name"],
    ["ab", "--features", "profile"],
    ["ab", "--features", "stats-foo"],
    ["ab", "--features", "a", "extra.json"],
    ["run", "--features", "a", "extra.json"],
  ]) {
    assert.throws(() => readOptions(args));
  }
});

test("--no-build skips the renderer build only for run", () => {
  assert.equal(readOptions(["run"]).noBuild, false);
  assert.equal(readOptions(["run", "--no-build"]).noBuild, true);
  for (const args of [
    ["ab", "--features", "branch-b", "--no-build"],
    ["compare", "a.json", "b.json", "--no-build"],
    ["run", "--no-build", "--features", "branch-b"],
  ]) {
    assert.throws(() => readOptions(args), /--no-build/);
  }
});

test("feature selection validates against the renderer manifest", () => {
  const definitions = { default: [], "branch-a": [], "branch-b": [] };
  assert.deepEqual(validateFeatureSelection(definitions, ["branch-b"], "ab"), [
    "default",
  ]);
  assert.deepEqual(validateFeatureSelection(definitions, [], "run"), [
    "default",
  ]);
  assert.throws(
    () => validateFeatureSelection({ default: ["branch-b"] }, [], "ab"),
    /empty default feature set/,
  );
  assert.deepEqual(
    validateFeatureSelection({ default: ["branch-b"] }, [], "run"),
    ["branch-b", "default"],
  );
  assert.throws(
    () => validateFeatureSelection(definitions, ["nope"], "run"),
    /Unknown Cargo feature/,
  );
  for (const command of ["run", "ab"]) {
    assert.throws(
      () =>
        validateFeatureSelection(
          { default: ["shared"], shared: [] },
          ["shared"],
          command,
        ),
      /already enabled by default/,
    );
  }
});

test("build environment recording excludes volatile values", () => {
  assert.equal("VERSION" in BUILD_ENV, false);
  assert.equal(BUILD_ENV.PENPOT_WASM_FUNCTION_NAMES, "0");
  assert.deepEqual(
    recordAmbientEnv({
      RUSTFLAGS: "--deny warnings",
      CARGO_HOME: "/home/penpot/.cargo",
      CARGO_PROFILE_RELEASE_DEBUG: "true",
      EMSDK: "/opt/emsdk",
      EMSDK_CLANG: "clang",
      PATH: "/usr/bin",
      VERSION: "volatile",
      PENPOT_WASM_PREPARED: "1",
    }),
    {
      RUSTFLAGS: "--deny warnings",
      CARGO_HOME: "/home/penpot/.cargo",
      CARGO_PROFILE_RELEASE_DEBUG: "true",
      EMSDK: "/opt/emsdk",
      EMSDK_CLANG: "clang",
    },
  );
});

test("classifier separates continuation from matrix stops", () => {
  const complete = {
    status: "complete",
    metadata: { build: { durationMs: 5 } },
    cases: [{ attempts: [{}] }],
  };
  assert.deepEqual(classifyConfigResult(complete), {
    outcome: "continue",
    status: "complete",
  });
  assert.deepEqual(classifyConfigResult({ ...complete, status: "failed" }), {
    outcome: "continue",
    status: "failed",
  });
  assert.deepEqual(classifyConfigResult(null), {
    outcome: "stop",
    status: "unattempted",
    error: "configuration output is missing or invalid",
  });
  assert.deepEqual(classifyConfigResult({ status: "interrupted" }), {
    outcome: "stop",
    status: "interrupted",
  });
  assert.deepEqual(
    classifyConfigResult({
      status: "failed",
      metadata: { build: {} },
      cases: [],
    }),
    {
      outcome: "stop",
      status: "failed",
      error: "build or preflight failed before any suite ran",
    },
  );
  assert.deepEqual(
    classifyConfigResult({
      status: "failed",
      metadata: { build: { durationMs: 5 } },
      cases: [],
    }),
    {
      outcome: "stop",
      status: "failed",
      error: "suite recorded no attempts",
    },
  );
});

const abDefinitions = { default: [], "branch-a": [], "branch-b": [] };

async function abTempRoot() {
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "renderer-ab-test-"));
  return path.join(tmp, "matrix");
}

function abOptions(argv) {
  return readOptions(argv);
}

function stubManifest(definitions = abDefinitions) {
  return async () => definitions;
}

function stubRun(failSlugs = new Set()) {
  const calls = [];
  const runFn = async (options) => {
    calls.push(options);
    const slug = path.basename(options.output, ".json");
    const failed = failSlugs.has(slug);
    const result = failed
      ? {
          schemaVersion: 2,
          runId: `run-${slug}`,
          status: "failed",
          error: "./build failed (1)",
          metadata: { build: { mode: "release", features: [] } },
          cases: [],
        }
      : {
          schemaVersion: 2,
          runId: `run-${slug}`,
          status: "complete",
          metadata: {
            build: {
              mode: "release",
              features: options.features,
              durationMs: 5,
            },
          },
          cases: [
            {
              id: "rects/default/pan",
              status: "complete",
              attempts: [{ index: 0, warmup: false, status: "ok" }],
            },
          ],
        };
    await fs.mkdir(path.dirname(options.output), { recursive: true });
    await fs.writeFile(options.output, JSON.stringify(result));
    return failed ? 1 : 0;
  };
  return { calls, runFn };
}

function stubCompare(extra = []) {
  const calls = [];
  const compareFn = async (baseline, candidate, { diagnostic } = {}) => {
    calls.push({ baseline, candidate, diagnostic });
    return {
      schemaVersion: 1,
      compatible: false,
      diagnostic: diagnostic ?? false,
      mismatches: [
        {
          path: "metadata.build.features",
          baseline: baseline.metadata.build.features,
          candidate: candidate.metadata.build.features,
        },
        ...extra,
      ],
      warnings: [],
      cases: [],
    };
  };
  return { calls, compareFn };
}

test("ab refuses an existing output root before any preflight", async () => {
  const tmp = await fs.mkdtemp(path.join(os.tmpdir(), "renderer-ab-test-"));
  const nonEmpty = path.join(tmp, "full");
  await fs.mkdir(nonEmpty);
  await fs.writeFile(path.join(nonEmpty, "x"), "x");
  const filePath = path.join(tmp, "file.json");
  await fs.writeFile(filePath, "{}");
  const mustNotRun = () => {
    throw new Error("must not be called");
  };
  for (const output of [nonEmpty, filePath]) {
    await assert.rejects(
      ab(abOptions(["ab", "--features", "branch-b", "--output", output]), {
        runFn: mustNotRun,
        compareFn: mustNotRun,
        manifestFn: mustNotRun,
      }),
      /already exists|non-empty/,
    );
  }
  await fs.rm(tmp, { recursive: true, force: true });
});

test("ab dry run prints the plan without building", async () => {
  const output = await abTempRoot();
  const mustNotRun = () => {
    throw new Error("must not be called");
  };
  const code = await ab(
    abOptions([
      "ab",
      "--features",
      "branch-b",
      "--filter",
      "rects/default/pan",
      "--dry-run",
      "--output",
      output,
    ]),
    { runFn: mustNotRun, compareFn: mustNotRun, manifestFn: stubManifest() },
  );
  assert.equal(code, 0);
  await assert.rejects(fs.access(output), /ENOENT/);
  await fs.rm(path.dirname(output), { recursive: true, force: true });
});

test("ab confirm gate blocks wide matrices and empty filters", async () => {
  const runFn = () => {
    throw new Error("must not be called");
  };
  const wide = await abTempRoot();
  const narrow = await abTempRoot();
  await assert.rejects(
    ab(
      abOptions([
        "ab",
        "--features",
        "f1,f2,f3,f4",
        "--max-features",
        "4",
        "--filter",
        "rects",
        "--output",
        wide,
      ]),
      {
        runFn,
        manifestFn: stubManifest({
          default: [],
          f1: [],
          f2: [],
          f3: [],
          f4: [],
        }),
      },
    ),
    /confirm/,
  );
  await assert.rejects(
    ab(abOptions(["ab", "--features", "branch-b", "--output", narrow]), {
      runFn,
      manifestFn: stubManifest(),
    }),
    /confirm/,
  );
  await fs.rm(path.dirname(wide), { recursive: true, force: true });
  await fs.rm(path.dirname(narrow), { recursive: true, force: true });
});

test("ab runs the matrix and writes configs, comparisons and index", async () => {
  const output = await abTempRoot();
  const { calls: runCalls, runFn } = stubRun();
  const { calls: compareCalls, compareFn } = stubCompare();
  const before = process.listenerCount("SIGINT");
  const code = await ab(
    abOptions([
      "ab",
      "--features",
      "branch-b",
      "--filter",
      "rects/default/pan",
      "--output",
      output,
    ]),
    { runFn, compareFn, manifestFn: stubManifest() },
  );
  assert.equal(code, 0);
  assert.equal(process.listenerCount("SIGINT"), before);
  assert.deepEqual(
    runCalls.map((options) => options.features),
    [[], ["branch-b"]],
  );
  assert.equal(compareCalls.length, 1);
  assert.equal(compareCalls[0].diagnostic, true);
  const first = JSON.parse(
    await fs.readFile(path.join(output, "configs", "0.json"), "utf8"),
  );
  assert.equal(first.runId, "run-0");
  const comparison = JSON.parse(
    await fs.readFile(path.join(output, "comparisons", "0__1.json"), "utf8"),
  );
  assert.deepEqual(comparison.edge, {
    from: "0",
    to: "1",
    toggledFeature: "branch-b",
  });
  assert.deepEqual(
    comparison.mismatches.filter((entry) => entry.expected),
    [
      {
        path: "metadata.build.features",
        baseline: [],
        candidate: ["branch-b"],
        expected: true,
        toggledFeature: "branch-b",
      },
    ],
  );
  const matrix = JSON.parse(
    await fs.readFile(path.join(output, "matrix.json"), "utf8"),
  );
  assert.equal(matrix.schemaVersion, 1);
  assert.deepEqual(matrix.features, ["branch-b"]);
  assert.deepEqual(
    matrix.configurations.map((c) => c.status),
    ["complete", "complete"],
  );
  assert.deepEqual(
    matrix.comparisons.map((c) => [c.from, c.to, c.status]),
    [["0", "1", "compared"]],
  );
  assert.equal(matrix.status, "complete");
  assert.equal(comparison.controlled, true);
  assert.equal(matrix.comparisons[0].controlled, true);
  await fs.rm(path.dirname(output), { recursive: true, force: true });
});

test("ab marks edges with unexpected diffs uncontrolled", async () => {
  const output = await abTempRoot();
  const { runFn } = stubRun();
  const { compareFn } = stubCompare([
    { path: "metadata.build.env", baseline: {}, candidate: {} },
  ]);
  const code = await ab(
    abOptions([
      "ab",
      "--features",
      "branch-b",
      "--filter",
      "rects/default/pan",
      "--output",
      output,
    ]),
    { runFn, compareFn, manifestFn: stubManifest() },
  );
  assert.equal(code, 0);
  const comparison = JSON.parse(
    await fs.readFile(path.join(output, "comparisons", "0__1.json"), "utf8"),
  );
  assert.equal(comparison.controlled, false);
  const matrix = JSON.parse(
    await fs.readFile(path.join(output, "matrix.json"), "utf8"),
  );
  assert.equal(matrix.comparisons[0].controlled, false);
  await fs.rm(path.dirname(output), { recursive: true, force: true });
});

test("ab interruption between configurations still writes the matrix", async () => {
  const output = await abTempRoot();
  const controller = new AbortController();
  const { runFn } = stubRun();
  const interruptAfterFirst = async (options) => {
    const code = await runFn(options);
    controller.abort(new Error("stopped"));
    return code;
  };
  const { compareFn } = stubCompare();
  const code = await ab(
    abOptions([
      "ab",
      "--features",
      "a,b",
      "--max-features",
      "2",
      "--filter",
      "rects",
      "--confirm",
      "--output",
      output,
    ]),
    {
      runFn: interruptAfterFirst,
      compareFn,
      manifestFn: stubManifest({ default: [], a: [], b: [] }),
      signal: controller.signal,
    },
  );
  assert.equal(code, 1);
  const matrix = JSON.parse(
    await fs.readFile(path.join(output, "matrix.json"), "utf8"),
  );
  assert.deepEqual(
    matrix.configurations.map((c) => [c.slug, c.status]),
    [
      ["00", "complete"],
      ["01", "unattempted"],
      ["11", "unattempted"],
      ["10", "unattempted"],
    ],
  );
  assert.ok(matrix.comparisons.every((c) => c.status === "not-attempted"));
  assert.equal(matrix.status, "failed");
  await fs.rm(path.dirname(output), { recursive: true, force: true });
});

test("ab stops on build failure and marks the rest unattempted", async () => {
  const output = await abTempRoot();
  const { runFn } = stubRun(new Set(["01"]));
  const { compareFn } = stubCompare();
  const code = await ab(
    abOptions([
      "ab",
      "--features",
      "b,a",
      "--max-features",
      "2",
      "--filter",
      "rects",
      "--confirm",
      "--output",
      output,
    ]),
    {
      runFn,
      compareFn,
      manifestFn: stubManifest({ default: [], a: [], b: [] }),
    },
  );
  assert.equal(code, 1);
  const matrix = JSON.parse(
    await fs.readFile(path.join(output, "matrix.json"), "utf8"),
  );
  assert.deepEqual(
    matrix.configurations.map((c) => [c.slug, c.status]),
    [
      ["00", "complete"],
      ["01", "failed"],
      ["11", "unattempted"],
      ["10", "unattempted"],
    ],
  );
  assert.deepEqual(
    matrix.comparisons.map((c) => [c.from, c.to, c.status]),
    [
      ["00", "10", "not-attempted"],
      ["00", "01", "compared"],
      ["01", "11", "not-attempted"],
      ["10", "11", "not-attempted"],
    ],
  );
  assert.equal(matrix.status, "failed");
  await fs.rm(path.dirname(output), { recursive: true, force: true });
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
