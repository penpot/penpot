// Shared by the benchmark page and offline Node reports. No browser work or I/O.
export const STATISTICAL_METHOD = Object.freeze({
  version: 1,
  name: "percentile-bootstrap-of-medians",
  confidence: 0.95,
  resamples: 2000,
  seed: 0x70656e70,
  minimumIntervalSamples: 10,
  minimumP95Samples: 100,
  minimumP99Samples: 500,
});

export const STATISTICAL_WARNINGS = Object.freeze([
  "Intervals are exploratory, nominal 95%, and conditional on these observed runs; attempts can be correlated.",
  "Independent runs can drift with system load and thermal state. Confirm important changes with repeated complete runs.",
]);

export function statisticalMethod(options = {}) {
  const method = { ...STATISTICAL_METHOD, ...options };
  if (
    !Number.isInteger(method.resamples) ||
    method.resamples < 100 ||
    method.resamples > 10000
  ) {
    throw new Error("resamples must be an integer between 100 and 10000");
  }
  if (
    !Number.isInteger(method.seed) ||
    method.seed < 0 ||
    method.seed > 0xffffffff
  ) {
    throw new Error("seed must be a uint32 integer");
  }
  // Only the resampling count and seed are configurable; support thresholds and
  // interval interpretation form part of the versioned statistical method.
  return {
    ...STATISTICAL_METHOD,
    resamples: method.resamples,
    seed: method.seed,
  };
}

function randomGenerator(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let value = Math.imul(state ^ (state >>> 15), 1 | state);
    value ^= value + Math.imul(value ^ (value >>> 7), 61 | value);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function quantile(sorted, probability) {
  if (!sorted.length) return null;
  const position = (sorted.length - 1) * probability;
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower);
}

function sorted(values) {
  return [...values].sort((a, b) => a - b);
}

function interval(values) {
  const ordered = sorted(values);
  return { low: quantile(ordered, 0.025), high: quantile(ordered, 0.975) };
}

function resampleMedian(values, random) {
  const sample = Array.from(
    { length: values.length },
    () => values[Math.floor(random() * values.length)],
  );
  return quantile(sorted(sample), 0.5);
}

export function summarize(values, options = {}) {
  const method = statisticalMethod(options);
  const ordered = sorted(
    values.filter((value) => Number.isFinite(value) && value >= 0),
  );
  const n = ordered.length;
  const median = quantile(ordered, 0.5);
  const mean = n ? ordered.reduce((sum, value) => sum + value / n, 0) : null;
  const summary = {
    n,
    invalid: values.length - n,
    mean,
    median,
    min: n ? ordered[0] : null,
    max: n ? ordered[n - 1] : null,
    standardDeviation:
      n > 1
        ? Math.sqrt(
            ordered.reduce(
              (sum, value) => sum + (value - mean) ** 2 / (n - 1),
              0,
            ),
          )
        : null,
    mad: n
      ? quantile(sorted(ordered.map((value) => Math.abs(value - median))), 0.5)
      : null,
    medianInterval: null,
    p95: null,
    p99: null,
    suppressed: {},
  };
  if (n >= method.minimumIntervalSamples) {
    const random = randomGenerator(method.seed);
    summary.medianInterval = interval(
      Array.from({ length: method.resamples }, () =>
        resampleMedian(ordered, random),
      ),
    );
  } else {
    summary.suppressed.medianInterval = `Requires at least ${method.minimumIntervalSamples} valid attempts.`;
  }
  for (const [name, threshold, probability] of [
    ["p95", method.minimumP95Samples, 0.95],
    ["p99", method.minimumP99Samples, 0.99],
  ]) {
    if (n >= threshold) summary[name] = quantile(ordered, probability);
    else
      summary.suppressed[name] =
        `Requires at least ${threshold} valid attempts.`;
  }
  return summary;
}

export function metricNames(benchmarkCase) {
  return [
    ...new Set(
      benchmarkCase.attempts.flatMap((attempt) => [
        ...Object.keys(attempt.metrics ?? {}),
        ...Object.keys(attempt.invalidMetrics ?? {}),
      ]),
    ),
  ].sort();
}

export function observations(benchmarkCase, metric) {
  return benchmarkCase.attempts
    .filter((attempt) => !attempt.warmup)
    .map((attempt) => {
      const invalid = attempt.invalidMetrics ?? {};
      if (
        attempt.status === "failed" ||
        (attempt.status === "invalid" && !Object.keys(invalid).length) ||
        Object.hasOwn(invalid, metric)
      )
        return NaN;
      return attempt.metrics?.[metric] ?? NaN;
    });
}

export function summarizeRun(result, options = {}) {
  return {
    method: statisticalMethod(options),
    warnings: [...STATISTICAL_WARNINGS],
    cases: result.cases.map((benchmarkCase) => {
      const measured = benchmarkCase.attempts.filter(
        (attempt) => !attempt.warmup,
      );
      return {
        id: benchmarkCase.id,
        status: benchmarkCase.status,
        attempts: {
          measured: measured.length,
          warmup: benchmarkCase.attempts.length - measured.length,
          ok: measured.filter((attempt) => attempt.status === "ok").length,
          invalid: measured.filter((attempt) => attempt.status === "invalid")
            .length,
          failed: measured.filter((attempt) => attempt.status === "failed")
            .length,
        },
        metrics: Object.fromEntries(
          metricNames(benchmarkCase).map((metric) => [
            metric,
            summarize(observations(benchmarkCase, metric), options),
          ]),
        ),
      };
    }),
  };
}

export function compareObservations(
  baselineValues,
  candidateValues,
  options = {},
) {
  const method = statisticalMethod(options);
  const baseline = summarize(baselineValues, options);
  const candidate = summarize(candidateValues, options);
  const result = {
    baseline,
    candidate,
    absoluteChange: null,
    relativeChangePercent: null,
    absoluteInterval: null,
    relativeIntervalPercent: null,
    suppressed: {},
  };
  if (!baseline.n || !candidate.n) {
    result.suppressed.comparison = "Both runs require valid observations.";
    return result;
  }
  result.absoluteChange = candidate.median - baseline.median;
  if (baseline.median === 0)
    result.suppressed.relativeChangePercent = "Baseline median is zero.";
  else
    result.relativeChangePercent =
      (100 * result.absoluteChange) / baseline.median;
  if (
    baseline.n < method.minimumIntervalSamples ||
    candidate.n < method.minimumIntervalSamples
  ) {
    result.suppressed.intervals = `Each run requires at least ${method.minimumIntervalSamples} valid attempts.`;
    return result;
  }
  const before = baselineValues.filter(
    (value) => Number.isFinite(value) && value >= 0,
  );
  const after = candidateValues.filter(
    (value) => Number.isFinite(value) && value >= 0,
  );
  const random = randomGenerator(method.seed);
  const differences = [];
  const relative = [];
  for (let index = 0; index < method.resamples; index++) {
    // Independent draws preserve the unpaired nature of separate runs.
    const first = resampleMedian(before, random);
    const second = resampleMedian(after, random);
    differences.push(second - first);
    if (first !== 0) relative.push((100 * (second - first)) / first);
  }
  result.absoluteInterval = interval(differences);
  if (baseline.median !== 0 && relative.length === method.resamples)
    result.relativeIntervalPercent = interval(relative);
  else
    result.suppressed.relativeIntervalPercent =
      "Baseline median or a resampled baseline median is zero.";
  return result;
}

function display(value) {
  return value === null ? "n/a" : value.toFixed(3);
}

export function formatSummary(summary) {
  const lines = [
    `Statistics: ${summary.method.name} v${summary.method.version}; ${summary.method.resamples} resamples, seed ${summary.method.seed}.`,
    ...summary.warnings,
  ];
  for (const benchmarkCase of summary.cases) {
    lines.push(`${benchmarkCase.id} (${benchmarkCase.status})`);
    for (const [name, metric] of Object.entries(benchmarkCase.metrics)) {
      lines.push(
        `  ${name}: median ${display(metric.median)}, mean ${display(metric.mean)}, range ${display(metric.min)}–${display(metric.max)}, SD ${display(metric.standardDeviation)}, MAD ${display(metric.mad)}; ${metric.n} valid, ${metric.invalid} invalid`,
      );
      lines.push(
        `    median interval: ${metric.medianInterval ? `[${display(metric.medianInterval.low)}, ${display(metric.medianInterval.high)}]` : metric.suppressed.medianInterval}; p95: ${metric.p95 === null ? metric.suppressed.p95 : display(metric.p95)}; p99: ${metric.p99 === null ? metric.suppressed.p99 : display(metric.p99)}`,
      );
    }
  }
  return lines.join("\n");
}
