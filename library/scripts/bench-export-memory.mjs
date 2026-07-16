#!/usr/bin/env node

// Measures the heap of a synthetic version 2 export, to show how much of the
// document the export holds while it writes.
//
// Run from `library/`, with the bundle built:
//
//   pnpm run build
//   node scripts/bench-export-memory.mjs 5000 65536
//   node scripts/bench-export-memory.mjs 20000 65536
//
// The export streams to a sink that keeps nothing, so the number does not
// include the package. Run it at two document sizes and the same chunk budget
// and compare the deltas: a delta that does not grow with `shapes` is evidence
// that the export does not hold the document.
//
// What the numbers are, and are not: the heap is sampled with a timer while the
// export runs, and the export yields the event loop between zip entries, so the
// samples see the live set between chunks. `heapUsed` includes garbage the
// collector has not released yet and depends on when it runs, so a delta is an
// observation, not a proof of a bound, and a chunk serialized synchronously has
// its own peak unobserved. The bundle itself needs a large heap only to load
// (malli compiles its schemas at startup), so a bounded `--max-old-space-size`
// cannot isolate the export.

import * as penpot from "#self";

const shapes = Number(process.argv[2] ?? 20000);
const chunkSize = Number(process.argv[3] ?? 5 * 1024 * 1024);

if (!Number.isInteger(shapes) || shapes <= 0) {
  throw new Error("usage: bench-export-memory.mjs [shapes] [chunk-size]");
}

if (!Number.isInteger(chunkSize) || chunkSize <= 0) {
  throw new Error("usage: bench-export-memory.mjs [shapes] [chunk-size]");
}

const context = penpot.createBuildContext();
context.addFile({ name: "bench" });
context.addPage({ name: "bench" });
for (let i = 0; i < shapes; i++) {
  context.addRect({ name: `rect ${i}`, x: i, y: 0, width: 10, height: 10 });
}

// A sink that keeps nothing: the package does not live in the heap while it is
// written, which is the point of measuring the export and not the artifact.
const sink = new WritableStream({
  write() {},
});

const baseline = process.memoryUsage().heapUsed;
const samples = [baseline];
const sample = () => samples.push(process.memoryUsage().heapUsed);
const timer = setInterval(sample, 5);

const started = performance.now();
try {
  await penpot.exportStream(context, sink, { version: 2, chunkSize });
} finally {
  clearInterval(timer);
}

const peak = Math.max(...samples);
const elapsed = Math.round(performance.now() - started);
const mb = (bytes) => Math.round(bytes / 1024 / 1024);

console.log(
  `shapes=${shapes} chunk=${chunkSize} ms=${elapsed} ` +
  `samples=${samples.length} ` +
  `baseline=${mb(baseline)}MiB peak=${mb(peak)}MiB ` +
  `delta=${mb(peak - baseline)}MiB ` +
  `rss=${mb(process.memoryUsage().rss)}MiB`,
);
