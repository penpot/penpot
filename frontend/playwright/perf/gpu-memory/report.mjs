#!/usr/bin/env node
// Compares GPU memory runs written by gpu-memory.spec.js.
//
//   node playwright/perf/gpu-memory/report.mjs [results-dir]
//     [--baseline <label>] [--out <file.html>] [--keep-warmup] [--json]
//
// Prints one table per engine to stdout and writes a self-contained HTML
// report with the time series. Every metric is "lower is better".
import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

const argv = process.argv.slice(2);
const flag = (name) => argv.includes(name);
const option = (name) => {
  const i = argv.indexOf(name);
  return i >= 0 ? argv[i + 1] : undefined;
};
const positional = argv.filter(
  (a, i) =>
    !a.startsWith("--") && !["--baseline", "--out"].includes(argv[i - 1]),
);

const RESULTS_DIR = positional[0] ?? "playwright/perf/results";
const OUT = option("--out") ?? "playwright/perf/report.html";
const KEEP_WARMUP = flag("--keep-warmup");
const NOISE_PCT = 5;
// Differences below these are noise whatever the percentage, so metrics that
// sit near zero (growth, idle minus load) can't flag a few MiB as a change.
const NOISE_ABS = { bytes: 8 * 1024 ** 2, count: 5 };

const MiB = 1024 ** 2;

// ---------------------------------------------------------------- metrics

const max = (xs) => (xs.length ? Math.max(...xs) : null);
const nums = (xs) =>
  xs.filter((x) => typeof x === "number" && Number.isFinite(x));

function drmResident(os) {
  const drm = os?.drm ?? {};
  const pick = (prefix) =>
    Object.entries(drm)
      .filter(([k]) => k.startsWith(prefix))
      .reduce((acc, [, v]) => acc + v, 0);
  const keys = Object.keys(drm);
  if (keys.some((k) => k.startsWith("resident-"))) return pick("resident-");
  if (keys.some((k) => k.startsWith("memory-"))) return pick("memory-");
  return null;
}

function nvidiaBytes(os) {
  const nv = os?.nvidia;
  if (!nv) return null;
  const perProcess = Object.values(nv.perProcess ?? {});
  return perProcess.length
    ? perProcess.reduce((a, b) => a + b, 0)
    : nv.usedBytes;
}

// Series plotted in the HTML report, per sample.
const SERIES = [
  {
    key: "webgl",
    title: "WebGL memory (textures + renderbuffers + buffers)",
    unit: "bytes",
    get: (s) => s.web?.totalBytes,
  },
  {
    key: "allocs",
    title: "WebGL storage allocations (cumulative)",
    unit: "count",
    get: (s) => s.web?.allocs?.total,
  },
  {
    key: "skia",
    title: "Skia resource cache",
    unit: "bytes",
    get: (s) => s.web?.skiaCache?.bytes,
  },
  {
    key: "gpuRss",
    title: "RSS of processes holding the GPU",
    unit: "bytes",
    get: (s) => s.os?.gpuProcessRss,
  },
  {
    key: "drm",
    title: "DRM resident memory (kernel fdinfo)",
    unit: "bytes",
    get: (s) => drmResident(s.os),
  },
  {
    key: "dmabuf",
    title: "dma-buf bytes shared by the browser",
    unit: "bytes",
    get: (s) => s.os?.dmabuf?.bytes,
  },
  {
    key: "nvidia",
    title: "NVIDIA memory (nvidia-smi)",
    unit: "bytes",
    get: (s) => nvidiaBytes(s.os),
  },
];

function phaseSamples(run, phase) {
  return run.samples.filter((s) => s.phase === phase);
}

function lastOf(run, phase, get) {
  const xs = phaseSamples(run, phase);
  return xs.length ? (get(xs.at(-1)) ?? null) : null;
}

function peak(run, get) {
  return max(nums(run.samples.filter((s) => s.phase !== "purge").map(get)));
}

// Growth over one full cycle of page sizes: the same size is revisited
// every PAGE_SIZES.length samples, so any difference is memory not given back.
function perCycleGrowth(run, get, cycle = 4) {
  const xs = phaseSamples(run, "resize").map(get);
  const diffs = [];
  for (let i = cycle; i < xs.length; i++) {
    if (typeof xs[i] === "number" && typeof xs[i - cycle] === "number") {
      diffs.push(xs[i] - xs[i - cycle]);
    }
  }
  return diffs.length ? diffs.reduce((a, b) => a + b, 0) / diffs.length : null;
}

function perResize(run, get) {
  const load = lastOf(run, "load", get);
  const end = lastOf(run, "resize", get);
  const n = phaseSamples(run, "resize").length;
  return load == null || end == null || n === 0 ? null : (end - load) / n;
}

const webgl = (s) => s.web?.totalBytes;
const allocs = (s) => s.web?.allocs?.total;
const allocBytes = (s) => s.web?.allocatedBytesTotal;

const METRICS = [
  {
    key: "webglPeak",
    title: "WebGL memory, peak",
    unit: "bytes",
    get: (r) => peak(r, webgl),
  },
  {
    key: "webglIdle",
    title: "WebGL memory, idle",
    unit: "bytes",
    get: (r) => lastOf(r, "idle", webgl),
  },
  {
    key: "texturePeak",
    title: "Texture memory, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.web?.textures?.bytes),
  },
  {
    key: "drawingBufferPeak",
    title: "Drawing buffer (estimate), peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.web?.drawingBuffer?.bytesEstimate),
  },
  {
    key: "allocsPerResize",
    title: "Allocations per page resize",
    unit: "count",
    get: (r) => perResize(r, allocs),
  },
  {
    key: "allocBytesPerResize",
    title: "Bytes allocated per page resize",
    unit: "bytes",
    get: (r) => perResize(r, allocBytes),
  },
  {
    key: "allocsTotal",
    title: "Allocations, whole session",
    unit: "count",
    get: (r) => lastOf(r, "idle", allocs),
  },
  {
    key: "allocBytesTotal",
    title: "Bytes allocated, whole session",
    unit: "bytes",
    get: (r) => lastOf(r, "idle", allocBytes),
  },
  {
    key: "webglCycleGrowth",
    title: "WebGL growth per resize cycle",
    unit: "bytes",
    get: (r) => perCycleGrowth(r, webgl),
  },
  {
    key: "skiaCachePeak",
    title: "Skia resource cache, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.web?.skiaCache?.bytes),
  },
  {
    key: "skiaPurgeableIdle",
    title: "Skia purgeable bytes, idle",
    unit: "bytes",
    get: (r) => lastOf(r, "idle", (s) => s.web?.skiaCache?.purgeableBytes),
  },
  {
    key: "wasmHeapPeak",
    title: "WASM heap, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.web?.wasmHeapBytes),
  },
  {
    key: "gpuRssPeak",
    title: "GPU-process RSS, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.os?.gpuProcessRss),
  },
  {
    key: "gpuRssGrowth",
    title: "GPU-process RSS, idle minus load",
    unit: "bytes",
    get: (r) => {
      const a = lastOf(r, "load", (s) => s.os?.gpuProcessRss);
      const b = lastOf(r, "idle", (s) => s.os?.gpuProcessRss);
      return a == null || b == null ? null : b - a;
    },
  },
  {
    key: "browserRssPeak",
    title: "Browser RSS (all processes), peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.os?.browserRss),
  },
  {
    key: "drmPeak",
    title: "DRM resident, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => drmResident(s.os)),
  },
  {
    key: "drmIdle",
    title: "DRM resident, idle",
    unit: "bytes",
    get: (r) => lastOf(r, "idle", (s) => drmResident(s.os)),
  },
  {
    key: "dmabufPeak",
    title: "dma-buf bytes, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => s.os?.dmabuf?.bytes),
  },
  {
    key: "nvidiaPeak",
    title: "NVIDIA memory, peak",
    unit: "bytes",
    get: (r) => peak(r, (s) => nvidiaBytes(s.os)),
  },
  {
    key: "purgeFreed",
    title: "Freed by free_gpu_resources (DRM)",
    unit: "bytes",
    informative: true,
    get: (r) => {
      const idle = lastOf(r, "idle", (s) => drmResident(s.os));
      const purge = lastOf(r, "purge", (s) => drmResident(s.os));
      return idle == null || purge == null ? null : idle - purge;
    },
  },
];

// ------------------------------------------------------------- aggregation

function median(xs) {
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function stats(values) {
  const xs = nums(values);
  if (xs.length === 0) return null;
  return {
    median: median(xs),
    min: Math.min(...xs),
    max: Math.max(...xs),
    n: xs.length,
  };
}

function verdict(base, other, informative, unit) {
  if (!base || !other) return { delta: null, verdict: "—" };
  const delta =
    base.median === 0
      ? other.median === 0
        ? 0
        : Infinity
      : ((other.median - base.median) / Math.abs(base.median)) * 100;
  if (informative) return { delta, verdict: "info" };
  const overlap = other.min <= base.max && base.min <= other.max;
  const small = Math.abs(other.median - base.median) < (NOISE_ABS[unit] ?? 0);
  if (Math.abs(delta) < NOISE_PCT || small || overlap)
    return { delta, verdict: "noise" };
  return { delta, verdict: delta < 0 ? "lower" : "higher" };
}

async function loadRuns(dir) {
  const runs = [];
  for (const label of await readdir(dir, { withFileTypes: true })) {
    if (!label.isDirectory()) continue;
    for (const file of await readdir(join(dir, label.name))) {
      if (!file.endsWith(".json")) continue;
      const run = JSON.parse(
        await readFile(join(dir, label.name, file), "utf8"),
      );
      runs.push(run);
    }
  }
  return runs;
}

function group(runs) {
  const groups = new Map();
  for (const run of runs) {
    const key = `${run.meta.engine}\u0000${run.meta.label}`;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(run);
  }
  for (const list of groups.values())
    list.sort((a, b) => a.meta.repeat - b.meta.repeat);
  return groups;
}

function measured(list) {
  // Repeat 0 runs on a cold GPU/shader cache; drop it when there are enough.
  return !KEEP_WARMUP && list.length >= 3
    ? list.filter((r) => r.meta.repeat !== 0)
    : list;
}

// ---------------------------------------------------------------- format

function fmt(value, unit) {
  if (value == null) return "—";
  if (unit === "bytes") {
    const mib = value / MiB;
    return `${Math.abs(mib) >= 100 ? mib.toFixed(0) : mib.toFixed(1)} MiB`;
  }
  return Math.abs(value) >= 100 ? value.toFixed(0) : value.toFixed(1);
}

function fmtDelta(delta) {
  if (delta == null) return "";
  if (!Number.isFinite(delta)) return "new";
  return `${delta > 0 ? "+" : ""}${delta.toFixed(1)}%`;
}

function pad(s, n) {
  return `${String(s).padEnd(n - 1)} `;
}

// ------------------------------------------------------------------- main

const runs = await loadRuns(RESULTS_DIR);
if (runs.length === 0) {
  console.error(`No runs found under ${RESULTS_DIR}`);
  process.exit(1);
}

const groups = group(runs);
const engines = [...new Set(runs.map((r) => r.meta.engine))].sort();
const labelsByDate = [
  ...new Set(
    [...runs]
      .sort((a, b) => a.meta.date.localeCompare(b.meta.date))
      .map((r) => r.meta.label),
  ),
];
const baseline =
  option("--baseline") ??
  (labelsByDate.includes("develop") ? "develop" : labelsByDate[0]);
const labels = [baseline, ...labelsByDate.filter((l) => l !== baseline)];

const summary = {};
for (const engine of engines) {
  summary[engine] = {};
  for (const label of labels) {
    const list = groups.get(`${engine}\u0000${label}`);
    if (!list) continue;
    const used = measured(list);
    summary[engine][label] = {
      runs: list.length,
      measured: used.length,
      meta: list[0].meta,
      metrics: Object.fromEntries(
        METRICS.map((m) => [m.key, stats(used.map(m.get))]),
      ),
    };
  }
}

for (const engine of engines) {
  const byLabel = summary[engine];
  const present = labels.filter((l) => byLabel[l]);
  console.log(`\n## ${engine}`);
  for (const label of present) {
    const { meta, runs: n, measured: used } = byLabel[label];
    console.log(
      `  ${label}: ${used}/${n} runs, commit ${meta.commit}, wasm ${meta.wasm}, ${meta.renderer?.renderer ?? "unknown renderer"}`,
    );
  }
  const header = [pad("metric", 38), ...present.map((l) => pad(l, 26))].join(
    "",
  );
  console.log(`\n${header}`);
  for (const m of METRICS) {
    const base = byLabel[baseline]?.metrics[m.key];
    if (present.every((l) => !byLabel[l].metrics[m.key])) continue;
    const cells = present.map((label) => {
      const st = byLabel[label].metrics[m.key];
      const value = fmt(st?.median, m.unit);
      if (label === baseline) return pad(value, 26);
      const v = verdict(base, st, m.informative, m.unit);
      return pad(`${value} ${fmtDelta(v.delta)} ${v.verdict}`, 26);
    });
    console.log([pad(m.title, 38), ...cells].join(""));
  }
}

if (flag("--json")) {
  await writeFile(
    OUT.replace(/\.html$/, ".json"),
    JSON.stringify(summary, null, 2),
  );
}

// ------------------------------------------------------------------- html

const COLORS = ["--c1", "--c2", "--c3", "--c4", "--c5", "--c6"];
const colorOf = (label) =>
  `var(${COLORS[labels.indexOf(label) % COLORS.length]})`;

const esc = (s) =>
  String(s).replace(
    /[&<>"]/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c],
  );

function chart(engine, series) {
  const lines = [];
  let maxX = 0;
  let maxY = 0;
  let phases = null;
  for (const label of labels) {
    for (const run of measured(groups.get(`${engine}\u0000${label}`) ?? [])) {
      const ys = run.samples.map(series.get);
      if (nums(ys).length === 0) continue;
      maxX = Math.max(maxX, ys.length - 1);
      maxY = Math.max(maxY, ...nums(ys));
      lines.push({ label, ys });
      phases ??= run.samples.map((s) => s.phase);
    }
  }
  if (lines.length === 0) return "";

  const W = 560,
    H = 200,
    L = 64,
    R = 12,
    T = 12,
    B = 28;
  const x = (i) => L + (maxX ? (i / maxX) * (W - L - R) : 0);
  const y = (v) => T + (H - T - B) * (1 - (maxY ? v / maxY : 0));
  const unitFmt = (v) =>
    series.unit === "bytes" ? `${(v / MiB).toFixed(0)} MiB` : v.toFixed(0);

  const bands = [];
  let start = 0;
  for (let i = 1; i <= phases.length; i++) {
    if (i === phases.length || phases[i] !== phases[start]) {
      const x0 = x(Math.max(0, start - 0.5));
      const x1 = x(Math.min(maxX, i - 0.5));
      const name = esc(phases[start]);
      bands.push(
        `<rect class="band${bands.length % 2 ? " alt" : ""}" x="${x0}" y="${T}" width="${Math.max(1, x1 - x0)}" height="${H - T - B}"><title>${name}</title></rect>` +
          // Narrow bands (single samples) keep only the tooltip.
          (x1 - x0 >= 36
            ? `<text class="phase" x="${(x0 + x1) / 2}" y="${H - 10}">${name}</text>`
            : ""),
      );
      start = i;
    }
  }

  const ticks = [0, 0.5, 1].map((f) => {
    const v = maxY * f;
    return (
      `<line class="grid" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/>` +
      `<text class="tick" x="${L - 6}" y="${y(v) + 4}">${unitFmt(v)}</text>`
    );
  });

  const paths = lines.map(({ label, ys }) => {
    const d = ys
      .map((v, i) =>
        typeof v === "number" ? `${x(i).toFixed(1)},${y(v).toFixed(1)}` : null,
      )
      .filter(Boolean)
      .join(" L");
    return `<path d="M${d}" stroke="${colorOf(label)}"><title>${esc(label)}</title></path>`;
  });

  return `<figure><figcaption>${esc(series.title)}</figcaption>
<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="${esc(series.title)}">${bands.join("")}${ticks.join("")}<g class="lines">${paths.join("")}</g></svg></figure>`;
}

function table(engine) {
  const byLabel = summary[engine];
  const present = labels.filter((l) => byLabel[l]);
  const head = present
    .map(
      (l) =>
        `<th><span class="swatch" style="background:${colorOf(l)}"></span>${esc(l)}</th>`,
    )
    .join("");
  const rows = METRICS.filter((m) =>
    present.some((l) => byLabel[l].metrics[m.key]),
  ).map((m) => {
    const base = byLabel[baseline]?.metrics[m.key];
    const cells = present.map((label) => {
      const st = byLabel[label].metrics[m.key];
      const range =
        st && st.n > 1
          ? `<small>${fmt(st.min, m.unit)} – ${fmt(st.max, m.unit)}</small>`
          : "";
      if (label === baseline)
        return `<td>${fmt(st?.median, m.unit)}${range}</td>`;
      const v = verdict(base, st, m.informative, m.unit);
      return `<td>${fmt(st?.median, m.unit)} <span class="chip ${v.verdict}">${fmtDelta(v.delta)} ${v.verdict}</span>${range}</td>`;
    });
    return `<tr><th scope="row">${esc(m.title)}</th>${cells.join("")}</tr>`;
  });
  return `<table><thead><tr><th>metric (median)</th>${head}</tr></thead><tbody>${rows.join("")}</tbody></table>`;
}

function metaList(engine) {
  const byLabel = summary[engine];
  return labels
    .filter((l) => byLabel[l])
    .map((l) => {
      const { meta, runs: n, measured: used } = byLabel[l];
      return `<li><b>${esc(l)}</b>: ${used}/${n} runs · commit ${esc(meta.commit)} · wasm ${esc(meta.wasm)} · ${esc(meta.engineVersion)} · ${esc(meta.renderer?.renderer ?? "unknown renderer")} · ${meta.viewport.width}×${meta.viewport.height}@${meta.dpr} · ${esc(meta.fixture)}</li>`;
    })
    .join("");
}

const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>GPU memory report</title>
<style>
:root{--bg:#fbfbfa;--fg:#1d1d1b;--muted:#6b6b66;--line:#e2e2de;--band:#f1f1ee;
--c1:#2a6fdb;--c2:#d9480f;--c3:#2b8a3e;--c4:#9c36b5;--c5:#c2255c;--c6:#1098ad;
--lower:#2b8a3e;--higher:#c92a2a;--noise:#868e96;color-scheme:light}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#161615;--fg:#ecece8;--muted:#9a9a94;--line:#33332f;--band:#1f1f1d;
--c1:#6ea8fe;--c2:#ff922b;--c3:#69db7c;--c4:#da77f2;--c5:#f783ac;--c6:#3bc9db;--lower:#69db7c;--higher:#ff6b6b;--noise:#adb5bd;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#161615;--fg:#ecece8;--muted:#9a9a94;--line:#33332f;--band:#1f1f1d;
--c1:#6ea8fe;--c2:#ff922b;--c3:#69db7c;--c4:#da77f2;--c5:#f783ac;--c6:#3bc9db;--lower:#69db7c;--higher:#ff6b6b;--noise:#adb5bd;color-scheme:dark}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
main{max-width:1200px;margin:0 auto;padding:24px 16px}h1{font-size:22px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 8px;text-transform:capitalize}
p.lead,ul.meta{color:var(--muted)}ul.meta{padding-left:18px;font-size:13px}
.scroll{overflow-x:auto}table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}
th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:right;white-space:nowrap}th[scope=row],thead th:first-child{text-align:left;font-weight:500}
td small{display:block;color:var(--muted);font-size:11px}
.chip{font-size:12px;padding:1px 6px;border-radius:10px;border:1px solid currentColor}.chip.lower{color:var(--lower)}.chip.higher{color:var(--higher)}.chip.noise,.chip.info,.chip.—{color:var(--noise)}
.swatch{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:6px;vertical-align:baseline}
.charts{display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,520px),1fr));gap:16px;margin-top:16px}
figure{margin:0}figcaption{font-size:13px;margin-bottom:4px}svg{width:100%;height:auto;display:block}
.band{fill:var(--band)}.band.alt{fill:transparent}.grid{stroke:var(--line)}.tick{fill:var(--muted);font-size:10px;text-anchor:end}
.phase{fill:var(--muted);font-size:10px;text-anchor:middle}.lines path{fill:none;stroke-width:1.5;opacity:.85}
</style></head><body><main>
<h1>GPU memory report</h1>
<p class="lead">Baseline <b>${esc(baseline)}</b>. Lower is better for every metric. A change counts only when medians differ by ≥${NOISE_PCT}% and the run ranges don't overlap; otherwise it's noise.${KEEP_WARMUP ? "" : " Repeat 0 is dropped as warm-up when there are 3+ runs."}</p>
${engines.map((e) => `<section><h2>${esc(e)}</h2><ul class="meta">${metaList(e)}</ul><div class="scroll">${table(e)}</div><div class="charts">${SERIES.map((s) => chart(e, s)).join("")}</div></section>`).join("")}
</main></body></html>`;

await writeFile(OUT, html);
console.log(`\nReport: ${OUT}`);
