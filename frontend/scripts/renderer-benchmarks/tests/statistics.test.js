import assert from "node:assert/strict";
import test from "node:test";
import {
  summarize,
  summarizeRun,
  formatSummary,
} from "../../../resources/wasm-playground/benchmarks/statistics.js";

test("summaries use sample deviation and unscaled median absolute deviation", () => {
  const summary = summarize([1, 2, 3, 4, 5]);
  assert.equal(summary.mean, 3);
  assert.equal(summary.median, 3);
  assert.equal(summary.standardDeviation, Math.sqrt(2.5));
  assert.equal(summary.mad, 1);
  assert.equal(summary.min, 1);
  assert.equal(summary.max, 5);
});

test("invalid numbers are counted and zero observations survive", () => {
  const summary = summarize([0, 2, NaN, Infinity, "3", null, -1]);
  assert.equal(summary.n, 2);
  assert.equal(summary.invalid, 5);
  assert.equal(summary.median, 1);
  assert.equal(summary.medianInterval, null);
  assert.match(summary.suppressed.medianInterval, /10/);
});

test("empty and singleton observations have no invented spread", () => {
  assert.equal(summarize([]).median, null);
  assert.equal(summarize([]).standardDeviation, null);
  assert.equal(summarize([2]).standardDeviation, null);
  assert.equal(summarize([2]).mad, 0);
});

test("bootstrap intervals are deterministic and tail support is explicit", () => {
  const values = Array.from({ length: 100 }, (_, index) => index + 1);
  const first = summarize(values);
  assert.deepEqual(first, summarize(values));
  assert.ok(first.medianInterval.low < first.median);
  assert.ok(first.medianInterval.high > first.median);
  assert.equal(first.p95, 95.05);
  assert.equal(first.p99, null);
  assert.match(first.suppressed.p99, /500/);
  assert.equal(summarize(Array(500).fill(3)).p99, 3);
});

test("run summaries exclude warmups, failed attempts and invalid metrics", () => {
  const result = summarizeRun({
    cases: [
      {
        id: "rects/load",
        status: "complete",
        attempts: [
          { warmup: true, status: "ok", metrics: { render: 1000 } },
          { warmup: false, status: "ok", metrics: { render: 10, upload: 0 } },
          {
            warmup: false,
            status: "invalid",
            metrics: { render: 0, upload: 2 },
            invalidMetrics: { render: "below resolution" },
          },
          { warmup: false, status: "failed", metrics: { render: 300 } },
        ],
      },
    ],
  });
  const summary = result.cases[0];
  assert.deepEqual(summary.attempts, {
    measured: 3,
    warmup: 1,
    ok: 1,
    invalid: 1,
    failed: 1,
  });
  assert.equal(summary.metrics.render.median, 10);
  assert.equal(summary.metrics.render.invalid, 2);
  assert.equal(summary.metrics.upload.median, 1);
  assert.equal(summary.metrics.upload.invalid, 1);
  assert.match(result.warnings.join(" "), /conditional/);
});

test("statistics reject unsafe bootstrap settings", () => {
  assert.throws(() => summarize([1], { resamples: Infinity }), /resamples/);
  assert.throws(() => summarize([1], { seed: NaN }), /seed/);
});

test("text summaries display the same observations and support limits as JSON", () => {
  const summary = summarizeRun({
    cases: [
      {
        id: "rects/load",
        status: "complete",
        attempts: [{ warmup: false, status: "ok", metrics: { render: 2 } }],
      },
    ],
  });
  const report = formatSummary(summary);
  assert.match(report, /median 2\.000/);
  assert.match(report, /1 valid, 0 invalid/);
  assert.match(report, /Requires at least 10 valid attempts/);
  assert.match(report, /2000 resamples/);
});

test("reports expose missing attempts and explain support limits once", () => {
  const summary = summarizeRun({
    cases: [
      {
        id: "rects/load",
        status: "failed",
        unattempted: 9,
        attempts: [
          {
            warmup: false,
            status: "failed",
            metrics: { render: 3, upload: 2 },
          },
        ],
      },
    ],
  });
  assert.equal(summary.cases[0].unattempted, 9);
  const report = formatSummary(summary);
  assert.match(report, /9 unattempted/);
  assert.match(report, /1 failed/);
  assert.equal(
    report.split("Requires at least 10 valid attempts").length - 1,
    1,
  );
});
