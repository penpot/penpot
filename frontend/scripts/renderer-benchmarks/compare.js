import {
  compareObservations,
  metricNames,
  observations,
  statisticalMethod,
  STATISTICAL_WARNINGS,
} from "../../resources/wasm-playground/benchmarks/statistics.js";

function record(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function requireValid(condition, message) {
  if (!condition) throw new Error(`Invalid benchmark result: ${message}`);
}

export function validateResult(result) {
  requireValid(
    record(result) && result.schemaVersion === 1,
    "expected schemaVersion 1",
  );
  requireValid(record(result.metadata), "metadata is required");
  for (const field of ["environment", "configuration"])
    requireValid(
      record(result.metadata[field]),
      `metadata.${field} is required`,
    );
  requireValid(Array.isArray(result.cases), "cases must be an array");
  const ids = new Set();
  for (const benchmarkCase of result.cases) {
    requireValid(record(benchmarkCase), "case must be an object");
    requireValid(
      typeof benchmarkCase.id === "string" &&
        benchmarkCase.id.length &&
        !ids.has(benchmarkCase.id),
      "case IDs must be unique nonempty strings",
    );
    ids.add(benchmarkCase.id);
    for (const field of ["scenarioId", "group", "preparation"])
      requireValid(
        typeof benchmarkCase[field] === "string" && benchmarkCase[field].length,
        `${benchmarkCase.id}.${field} is required`,
      );
    requireValid(
      Number.isInteger(benchmarkCase.scenarioVersion) &&
        benchmarkCase.scenarioVersion >= 1,
      "scenarioVersion must be a positive integer",
    );
    requireValid(
      Number.isInteger(benchmarkCase.sceneSeed) &&
        benchmarkCase.sceneSeed >= 0 &&
        benchmarkCase.sceneSeed <= 0xffffffff,
      "sceneSeed must be a uint32",
    );
    for (const field of ["params", "initialView"])
      requireValid(
        record(benchmarkCase[field]),
        `${benchmarkCase.id}.${field} is required`,
      );
    requireValid(
      benchmarkCase.interaction === null || record(benchmarkCase.interaction),
      "interaction must be an object or null",
    );
    requireValid(
      ["complete", "failed", "interrupted", "unattempted"].includes(
        benchmarkCase.status,
      ),
      "unknown case status",
    );
    requireValid(
      Array.isArray(benchmarkCase.attempts),
      "attempts must be an array",
    );
    for (const attempt of benchmarkCase.attempts) {
      requireValid(
        record(attempt) && ["ok", "invalid", "failed"].includes(attempt.status),
        "unknown attempt status",
      );
      requireValid(
        typeof attempt.warmup === "boolean",
        "attempt.warmup must be boolean",
      );
      requireValid(
        record(attempt.metrics),
        "attempt.metrics must be an object",
      );
      requireValid(
        attempt.invalidMetrics === undefined || record(attempt.invalidMetrics),
        "invalidMetrics must be an object",
      );
      for (const [name, value] of Object.entries(attempt.metrics))
        requireValid(
          (value === null &&
            Object.hasOwn(attempt.invalidMetrics ?? {}, name)) ||
            (Number.isFinite(value) && value >= 0),
          `metric ${name} must be finite and nonnegative, or explicitly invalid null`,
        );
      for (const reason of Object.values(attempt.invalidMetrics ?? {}))
        requireValid(
          typeof reason === "string" && reason.length,
          "invalid metric reasons must be nonempty strings",
        );
    }
  }
}

function differences(baseline, candidate, path, mismatches) {
  if (record(baseline) && record(candidate)) {
    for (const key of [
      ...new Set([...Object.keys(baseline), ...Object.keys(candidate)]),
    ].sort())
      differences(baseline[key], candidate[key], `${path}.${key}`, mismatches);
  } else if (Array.isArray(baseline) && Array.isArray(candidate)) {
    if (baseline.length !== candidate.length)
      mismatches.push({ path, baseline, candidate });
    else
      baseline.forEach((value, index) =>
        differences(value, candidate[index], `${path}[${index}]`, mismatches),
      );
  } else if (baseline !== candidate)
    mismatches.push({
      path,
      baseline: baseline ?? null,
      candidate: candidate ?? null,
    });
}

export class CompatibilityError extends Error {
  constructor(mismatches) {
    super(
      `Incompatible benchmark runs: ${mismatches.map((entry) => entry.path).join(", ")}. Use an explicit diagnostic comparison to inspect mismatches.`,
    );
    this.name = "CompatibilityError";
    this.mismatches = mismatches;
  }
}

export function compareRuns(
  baseline,
  candidate,
  { diagnostic = false, ...options } = {},
) {
  validateResult(baseline);
  validateResult(candidate);
  const mismatches = [];
  // Compare renderer revisions, but never silently mix debug/profiling builds
  // with ordinary release measurements.
  for (const field of ["mode", "features", "target"]) {
    differences(
      baseline.metadata.build?.[field],
      candidate.metadata.build?.[field],
      `metadata.build.${field}`,
      mismatches,
    );
  }
  for (const field of ["environment", "configuration", "metricSemantics"])
    differences(
      baseline.metadata[field],
      candidate.metadata[field],
      `metadata.${field}`,
      mismatches,
    );
  const firstCases = new Map(
    baseline.cases.map((benchmarkCase) => [benchmarkCase.id, benchmarkCase]),
  );
  const secondCases = new Map(
    candidate.cases.map((benchmarkCase) => [benchmarkCase.id, benchmarkCase]),
  );
  const caseIds = [
    ...new Set([...firstCases.keys(), ...secondCases.keys()]),
  ].sort();
  for (const id of caseIds) {
    const first = firstCases.get(id);
    const second = secondCases.get(id);
    if (!first || !second) {
      mismatches.push({
        path: `cases.${id}`,
        baseline: Boolean(first),
        candidate: Boolean(second),
      });
      continue;
    }
    for (const field of [
      "scenarioId",
      "scenarioVersion",
      "sceneSeed",
      "params",
      "initialView",
      "interaction",
      "group",
      "preparation",
      "environment",
      "metricSemantics",
    ])
      differences(
        first[field],
        second[field],
        `cases.${id}.${field}`,
        mismatches,
      );
    // A failed attempt may lack metrics. Only compare present metric sets when
    // both cases completed; failures still produce honest sample counts.
    if (first.status === "complete" && second.status === "complete")
      differences(
        metricNames(first),
        metricNames(second),
        `cases.${id}.metrics`,
        mismatches,
      );
  }
  if (mismatches.length && !diagnostic)
    throw new CompatibilityError(mismatches);
  return {
    schemaVersion: 1,
    compatible: mismatches.length === 0,
    diagnostic,
    mismatches,
    method: statisticalMethod(options),
    warnings: [
      ...(mismatches.length
        ? [
            "DIAGNOSTIC ONLY: these runs are incompatible; differences cannot establish a performance change.",
          ]
        : []),
      ...STATISTICAL_WARNINGS,
    ],
    cases: caseIds
      .filter((id) => firstCases.has(id) && secondCases.has(id))
      .map((id) => {
        const first = firstCases.get(id);
        const second = secondCases.get(id);
        const metrics = [
          ...new Set([...metricNames(first), ...metricNames(second)]),
        ].sort();
        return {
          id,
          metrics: Object.fromEntries(
            metrics.map((metric) => [
              metric,
              compareObservations(
                observations(first, metric),
                observations(second, metric),
                options,
              ),
            ]),
          ),
        };
      }),
  };
}

export function formatComparison(comparison) {
  const lines = [
    `Statistics: ${comparison.method.name} v${comparison.method.version}; ${comparison.method.resamples} resamples, seed ${comparison.method.seed}.`,
    ...comparison.warnings,
  ];
  for (const mismatch of comparison.mismatches)
    lines.push(
      `MISMATCH ${mismatch.path}: ${JSON.stringify(mismatch.baseline)} -> ${JSON.stringify(mismatch.candidate)}`,
    );
  const display = (value) => (value === null ? "n/a" : value.toFixed(3));
  for (const benchmarkCase of comparison.cases) {
    lines.push(benchmarkCase.id);
    for (const [name, metric] of Object.entries(benchmarkCase.metrics)) {
      lines.push(
        `  ${name}: median ${display(metric.baseline.median)} -> ${display(metric.candidate.median)}, change ${display(metric.absoluteChange)} (${display(metric.relativeChangePercent)}%); n=${metric.baseline.n}/${metric.candidate.n}`,
      );
      if (metric.absoluteInterval)
        lines.push(
          `    change interval: [${display(metric.absoluteInterval.low)}, ${display(metric.absoluteInterval.high)}]`,
        );
      if (metric.relativeIntervalPercent)
        lines.push(
          `    relative interval: [${display(metric.relativeIntervalPercent.low)}, ${display(metric.relativeIntervalPercent.high)}]%`,
        );
      for (const [field, reason] of Object.entries(metric.suppressed))
        lines.push(`    ${field}: ${reason}`);
    }
  }
  return lines.join("\n");
}
