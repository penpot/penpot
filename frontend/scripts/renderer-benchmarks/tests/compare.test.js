import assert from "node:assert/strict";
import test from "node:test";
import { compareRuns, formatComparison } from "../compare.js";
import { tagExpectedFeatureDiff } from "../compare.js";

function run(value = 10) {
  return {
    schemaVersion: 2,
    status: "complete",
    metadata: {
      git: { sha: "before", dirty: false },
      build: {
        mode: "release",
        featurePolicy: "manifest-defaults",
        features: [],
        defaultFeatures: ["default"],
        target: "frontend",
        env: { BUILD_MODE: "release" },
        ambientEnv: { CARGO_HOME: "/home/penpot/.cargo" },
      },
      environment: { chromium: "123", headless: true },
      configuration: { viewport: { width: 1920, height: 1080 }, dpr: 2 },
    },
    cases: [
      {
        id: "rects/default/load",
        scenarioId: "rects",
        scenarioVersion: 1,
        sceneSeed: 123,
        params: { count: 1000 },
        initialView: { x: 0, y: 0, zoom: 1 },
        interaction: null,
        group: "fresh-module-context",
        preparation: "fresh",
        environment: { webgl: { renderer: "GPU" } },
        status: "complete",
        attempts: Array.from({ length: 10 }, (_, index) => ({
          index,
          warmup: false,
          status: "ok",
          metrics: { render: value },
          invalidMetrics: {},
        })),
      },
    ],
  };
}

test("offline comparison allows intentional source and build changes", () => {
  const candidate = run(12);
  candidate.metadata.git.sha = "after";
  candidate.metadata.build.revision = "candidate";
  const result = compareRuns(run(), candidate);
  const metric = result.cases[0].metrics.render;
  assert.equal(result.compatible, true);
  assert.equal(metric.absoluteChange, 2);
  assert.equal(metric.relativeChangePercent, 20);
  assert.deepEqual(metric.absoluteInterval, { low: 2, high: 2 });
  assert.deepEqual(result, compareRuns(run(), candidate));
});

for (const [name, change] of [
  [
    "profiling features",
    (r) => {
      r.metadata.build.features = ["profile"];
    },
  ],
  [
    "build mode",
    (r) => {
      r.metadata.build.mode = "debug";
    },
  ],
  [
    "DPR",
    (r) => {
      r.metadata.configuration.dpr = 1;
    },
  ],
  [
    "browser",
    (r) => {
      r.metadata.environment.chromium = "124";
    },
  ],
  [
    "GPU",
    (r) => {
      r.cases[0].environment.webgl.renderer = "software";
    },
  ],
  [
    "seed",
    (r) => {
      r.cases[0].sceneSeed = 456;
    },
  ],
  [
    "version",
    (r) => {
      r.cases[0].scenarioVersion = 2;
    },
  ],
  [
    "workload",
    (r) => {
      r.cases[0].params.count = 2000;
    },
  ],
  [
    "preparation",
    (r) => {
      r.cases[0].preparation = "warm";
    },
  ],
  [
    "case set",
    (r) => {
      r.cases[0].id = "different";
    },
  ],
]) {
  test(`comparison refuses incompatible ${name} unless diagnostic override is explicit`, () => {
    const candidate = run();
    change(candidate);
    assert.throws(() => compareRuns(run(), candidate), {
      name: "CompatibilityError",
    });
    const result = compareRuns(run(), candidate, { diagnostic: true });
    assert.equal(result.compatible, false);
    assert.ok(result.mismatches.length > 0);
    assert.match(result.warnings.join(" "), /incompatible/i);
  });
}

test("object key order is immaterial to compatibility", () => {
  const candidate = run();
  candidate.metadata.configuration.viewport = { height: 1080, width: 1920 };
  assert.equal(compareRuns(run(), candidate).compatible, true);
});

test("relative change is unsupported when baseline is zero", () => {
  const metric = compareRuns(run(0), run(1)).cases[0].metrics.render;
  assert.equal(metric.relativeChangePercent, null);
  assert.equal(metric.relativeIntervalPercent, null);
  assert.match(metric.suppressed.relativeChangePercent, /zero/);
});

test("failed attempts never enter comparison observations", () => {
  const candidate = run(12);
  candidate.cases[0].attempts[0].status = "failed";
  const metric = compareRuns(run(), candidate).cases[0].metrics.render;
  assert.equal(metric.candidate.n, 9);
  assert.equal(metric.candidate.invalid, 1);
  assert.equal(metric.absoluteInterval, null);
});

test("comparisons warn when a run failed even if available metrics are compatible", () => {
  const candidate = run(12);
  candidate.status = "failed";
  candidate.cases[0].status = "failed";
  const result = compareRuns(run(), candidate);
  assert.equal(result.compatible, true);
  assert.equal(result.runs.candidate.status, "failed");
  assert.match(formatComparison(result), /candidate run is failed/i);
});

test("offline comparison rejects malformed results without diagnostic bypass", () => {
  const missingEnvironment = run();
  delete missingEnvironment.metadata.environment;
  const duplicateCase = run();
  duplicateCase.cases.push(structuredClone(duplicateCase.cases[0]));
  const invalidMetric = run();
  invalidMetric.cases[0].attempts[0].metrics.render = "12";
  for (const invalid of [
    null,
    {},
    { schemaVersion: 1 },
    { schemaVersion: 3 },
    missingEnvironment,
    duplicateCase,
    invalidMetric,
  ]) {
    assert.throws(
      () => compareRuns(invalid, run(), { diagnostic: true }),
      /Invalid benchmark result/,
    );
  }
});

test("text comparisons display changes, intervals and incompatibilities", () => {
  const candidate = run(12);
  candidate.metadata.configuration.dpr = 1;
  const report = formatComparison(
    compareRuns(run(), candidate, { diagnostic: true }),
  );
  assert.match(report, /DIAGNOSTIC ONLY/);
  assert.match(report, /MISMATCH metadata.configuration.dpr: 2 -> 1/);
  assert.match(report, /change 2\.000 \(20\.000%\)/);
  assert.match(report, /change interval: \[2\.000, 2\.000\]/);
});

for (const [name, change] of [
  ["default features", (r) => (r.metadata.build.defaultFeatures = [])],
  ["build env", (r) => (r.metadata.build.env.BUILD_MODE = "debug")],
  ["ambient env", (r) => (r.metadata.build.ambientEnv.CARGO_HOME = "/other")],
]) {
  test(`comparison refuses changed ${name} unless diagnostic override is explicit`, () => {
    const candidate = run();
    change(candidate);
    assert.throws(() => compareRuns(run(), candidate), {
      name: "CompatibilityError",
    });
    const result = compareRuns(run(), candidate, { diagnostic: true });
    assert.equal(result.compatible, false);
    assert.ok(result.mismatches.length > 0);
  });
}

test("ab edge tags the expected feature mismatch without suppressing it", () => {
  const candidate = run();
  candidate.metadata.build.features = ["branch-b"];
  candidate.metadata.build.featurePolicy = "explicit";
  assert.throws(() => compareRuns(run(), candidate), {
    name: "CompatibilityError",
  });
  const compared = compareRuns(run(), candidate, { diagnostic: true });
  assert.equal(compared.compatible, false);
  tagExpectedFeatureDiff(compared, "branch-b");
  const tagged = compared.mismatches.filter((entry) => entry.expected);
  assert.equal(tagged.length, 1);
  assert.deepEqual(tagged[0], {
    path: "metadata.build.features",
    baseline: [],
    candidate: ["branch-b"],
    expected: true,
    toggledFeature: "branch-b",
  });
});
