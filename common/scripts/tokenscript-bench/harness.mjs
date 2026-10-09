// Timing loop and job runner shared by the JavaScript engines.
//
// An engine is a module exporting
//
//   name, version            strings for the report
//   init()                   one-time setup (config, schemas), timed
//   build(fixture)           -> () => result      full resolution of the fixture
//   summarize(result)        -> {resolved, errors}
//   update(fixture)          -> (i) => void       optional incremental update
//
// The cljc engine (common/dev/tokenscript_bench.cljc) implements the same
// loop and writes the same result format.

import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const now = () => performance.now();
const gc = globalThis.gc ?? (() => {});

export function stats(samples) {
  const s = [...samples].sort((a, b) => a - b);
  const n = s.length;
  const at = (q) => s[Math.min(n - 1, Math.floor(q * n))];
  const mean = s.reduce((a, b) => a + b, 0) / n;
  const sd = Math.sqrt(s.reduce((a, b) => a + (b - mean) ** 2, 0) / n);
  return {
    runs: n,
    min: s[0],
    median: at(0.5),
    mean,
    p95: at(0.95),
    max: s[n - 1],
    sd,
  };
}

async function call(fn, i) {
  const r = fn(i);
  return r && typeof r.then === "function" ? await r : r;
}

// Cold first call, a warmup phase, then samples until the time budget is
// spent (at least `minRuns`, at most `maxRuns`). Slow cases (cold run
// longer than the budget) take 3 samples and skip most of the warmup.
export async function measure(fn, { budgetMs, warmupMs, minRuns, maxRuns }) {
  gc();
  let t0 = now();
  const first = await call(fn, 0);
  const cold = now() - t0;

  const slow = cold > budgetMs;
  const min = slow ? Math.min(minRuns, 3) : minRuns;
  const warmupBudget = slow ? 0 : warmupMs;

  let i = 1;
  let start = now();
  while (i < 3 || now() - start < warmupBudget) {
    await call(fn, i++);
    if (slow) break;
  }

  const samples = [];
  start = now();
  while (
    (samples.length < min || now() - start < budgetMs) &&
    samples.length < maxRuns
  ) {
    gc();
    t0 = now();
    await call(fn, i++);
    samples.push(now() - t0);
  }
  return { first, cold, ...stats(samples) };
}

export function readFixture(dir, id) {
  return JSON.parse(readFileSync(join(dir, `${id}.json`), "utf8"));
}

// Job file: {engine, fixturesDir, ids, ops, budgetMs, warmupMs, minRuns,
// maxRuns, out, ...engine options}
export async function runJob(engine, jobPath) {
  const job = JSON.parse(readFileSync(jobPath, "utf8"));
  const t0 = now();
  await engine.init(job);
  const initMs = now() - t0;
  const results = [];

  for (const id of job.ids) {
    const fixture = readFixture(job.fixturesDir, id);
    const opts = job;
    const base = {
      engine: job.engine,
      fixture: id,
      profile: fixture.profile,
      size: fixture.size,
    };

    if (job.ops.includes("build")) {
      try {
        const run = await engine.build(fixture);
        const m = await measure(run, opts);
        const summary = engine.summarize(m.first, fixture);
        delete m.first;
        results.push({ ...base, op: "build", ...m, ...summary });
      } catch (e) {
        results.push({ ...base, op: "build", error: String(e?.stack ?? e) });
      }
    }

    if (job.ops.includes("update") && fixture.update && engine.update) {
      try {
        const run = await engine.update(fixture);
        const m = await measure(run, opts);
        delete m.first;
        results.push({
          ...base,
          op: "update",
          dependents: fixture.update.dependents,
          ...m,
        });
      } catch (e) {
        results.push({ ...base, op: "update", error: String(e?.stack ?? e) });
      }
    }
  }

  const out = {
    engine: job.engine,
    version: engine.version(),
    runtime: `node ${process.version}`,
    initMs,
    results,
  };
  writeFileSync(job.out, JSON.stringify(out, null, 2));
}
