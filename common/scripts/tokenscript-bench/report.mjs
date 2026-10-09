// Renders benchmark results as markdown tables.
//
//   node report.mjs <results.json> [report.md]

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const engineLabels = {
  "ts-old": "TS (shipped)",
  "ts-local": "TS (local)",
  sd: "StyleDictionary",
  cljs: "Port (CLJS)",
  jvm: "Port (JVM)",
};

const label = (e) =>
  e.endsWith("-before")
    ? `${engineLabels[e.slice(0, -"-before".length)] ?? e} before`
    : (engineLabels[e] ?? e);

function fmt(ms) {
  if (ms === undefined || ms === null || Number.isNaN(ms)) return "—";
  if (ms < 1) return ms.toFixed(3);
  if (ms < 10) return ms.toFixed(2);
  if (ms < 100) return ms.toFixed(1);
  return Math.round(ms).toLocaleString("en-US");
}

const ratio = (a, b) => (a && b ? `${(a / b).toFixed(2)}×` : "—");

function table(headers, rows, align) {
  const al = align ?? headers.map((_, i) => (i === 0 ? "l" : "r"));
  const sep = al.map((a) => (a === "l" ? ":--" : "--:"));
  return [headers, sep, ...rows].map((r) => `| ${r.join(" | ")} |`).join("\n");
}

// Rows keyed by fixture, in profile order then size.
function fixtureRows(data) {
  const seen = new Map();
  for (const r of data.results) {
    if (!seen.has(r.fixture))
      seen.set(r.fixture, {
        fixture: r.fixture,
        profile: r.profile,
        size: r.size,
      });
  }
  const order = data.meta?.fixtures ?? [...seen.keys()];
  return order.filter((id) => seen.has(id)).map((id) => seen.get(id));
}

function lookup(data) {
  const m = new Map();
  for (const r of data.results) m.set(`${r.engine}|${r.fixture}|${r.op}`, r);
  return (engine, fixture, op) => m.get(`${engine}|${fixture}|${op}`);
}

function cell(r, key = "median", bold = false) {
  if (!r) return "—";
  if (r.error) return r.error.startsWith("timeout") ? "timeout" : "error";
  const v = fmt(r[key]);
  return bold ? `**${v}**` : v;
}

function bestEngine(engines, get, fixture, op) {
  let best = null;
  for (const e of engines) {
    const r = get(e, fixture, op);
    if (r && !r.error && (best === null || r.median < best.median))
      best = { engine: e, median: r.median };
  }
  return best?.engine;
}

export function render(data) {
  const engines = data.engines.map((e) => e.engine);
  const get = lookup(data);
  const rows = fixtureRows(data);
  const has = (e) => engines.includes(e);
  const out = [];

  out.push("# Tokenscript resolver benchmark", "");
  const m = data.meta ?? {};
  out.push(
    [
      `- Date: ${m.date ?? "?"}`,
      `- Penpot commit: ${m.commit ?? "?"}`,
      `- Old TS package from: ${m.oldRef ?? "?"}`,
      `- Machine: ${m.cpu ?? "?"} (${m.cores ?? "?"} cores, ${m.memoryGb ?? "?"} GB), ${m.platform ?? "?"}`,
      `- Budget per case: ${m.options?.budgetMs ?? "?"} ms after ${m.options?.warmupMs ?? "?"} ms warmup (JVM ${m.options?.jvmWarmupMs ?? "?"} ms), ${m.options?.minRuns ?? "?"} to ${m.options?.maxRuns ?? "?"} runs`,
      "- All times are wall-clock milliseconds; medians unless stated otherwise. Lower is better.",
      "- Node engines run each fixture in a fresh process; the JVM runs all fixtures in one process.",
      ...(m.baseline
        ? [
            `- Engines marked "before", and engines that were not run again, come from the baseline run of ${m.baseline.date} (${m.baseline.commit}) on the same fixtures.`,
          ]
        : []),
    ].join("\n"),
    "",
  );

  out.push("## Engines", "");
  out.push(
    table(
      ["Engine", "Version", "Runtime", "Init (ms)"],
      data.engines.map((e) => [
        label(e.engine),
        e.version,
        e.runtime,
        fmt(e.initMs),
      ]),
      ["l", "l", "l", "r"],
    ),
    "",
    "Init is the one-time setup: building the config from the bundled schemas (TS, port) or registering sd-transforms (StyleDictionary).",
    "",
  );

  out.push("## Profiles", "");
  out.push(
    table(
      ["Profile", "Contents"],
      Object.entries(data.meta?.profiles ?? {}).map(([p, d]) => [p, d]),
      ["l", "l"],
    ),
    "",
  );

  // Full build ---------------------------------------------------------------
  const speedups = [];
  if (has("cljs-before") && has("cljs"))
    speedups.push(["Port (CLJS) vs before", "cljs-before", "cljs"]);
  if (has("jvm-before") && has("jvm"))
    speedups.push(["Port (JVM) vs before", "jvm-before", "jvm"]);
  if (has("ts-old") && has("cljs"))
    speedups.push(["Port (CLJS) vs TS (shipped)", "ts-old", "cljs"]);
  if (has("sd") && has("cljs"))
    speedups.push(["Port (CLJS) vs StyleDictionary", "sd", "cljs"]);
  if (has("ts-local") && has("cljs"))
    speedups.push(["Port (CLJS) vs TS (local)", "ts-local", "cljs"]);

  out.push("## Full build: median (ms)", "");
  out.push(
    "Resolving the whole token set from scratch, as penpot does on every set or theme change. Fastest engine in bold. Speedup columns above 1× mean the port is faster. The median is a repeated build of the same set, so caches across builds count (the port caches parsed values); the cold table shows the first build.",
    "",
  );
  out.push(
    table(
      [
        "Fixture",
        "Tokens",
        ...engines.map(label),
        ...speedups.map((s) => s[0]),
      ],
      rows.map((r) => {
        const best = bestEngine(engines, get, r.fixture, "build");
        return [
          r.fixture,
          r.size.toLocaleString("en-US"),
          ...engines.map((e) =>
            cell(get(e, r.fixture, "build"), "median", e === best),
          ),
          ...speedups.map(([, a, b]) => {
            const ra = get(a, r.fixture, "build");
            const rb = get(b, r.fixture, "build");
            return ra?.error || rb?.error ? "—" : ratio(ra?.median, rb?.median);
          }),
        ];
      }),
    ),
    "",
  );

  out.push("## Full build: microseconds per token", "");
  out.push(
    table(
      ["Fixture", "Tokens", ...engines.map(label)],
      rows.map((r) => [
        r.fixture,
        r.size.toLocaleString("en-US"),
        ...engines.map((e) => {
          const x = get(e, r.fixture, "build");
          return x && !x.error ? fmt((x.median * 1000) / r.size) : cell(x);
        }),
      ]),
    ),
    "",
  );

  out.push("## Full build: p95 (ms)", "");
  out.push(
    table(
      ["Fixture", "Tokens", ...engines.map(label)],
      rows.map((r) => [
        r.fixture,
        r.size.toLocaleString("en-US"),
        ...engines.map((e) => cell(get(e, r.fixture, "build"), "p95")),
      ]),
    ),
    "",
  );

  const nodeEngines = engines.filter((e) => !e.startsWith("jvm"));
  if (nodeEngines.length) {
    out.push("## Cold first build (ms)", "");
    out.push(
      "First build in a fresh node process after init: no JIT warmup, as on the first token resolution after opening a file.",
      "",
    );
    out.push(
      table(
        ["Fixture", "Tokens", ...nodeEngines.map(label)],
        rows.map((r) => [
          r.fixture,
          r.size.toLocaleString("en-US"),
          ...nodeEngines.map((e) => cell(get(e, r.fixture, "build"), "cold")),
        ]),
      ),
      "",
    );
  }

  // Scaling ------------------------------------------------------------------
  const profilesInOrder = [...new Set(rows.map((r) => r.profile))];
  const scalingRows = [];
  for (const p of profilesInOrder) {
    const prs = rows
      .filter((r) => r.profile === p)
      .sort((a, b) => a.size - b.size);
    if (prs.length < 2) continue;
    const lo = prs[0];
    const hi = prs.at(-1);
    scalingRows.push([
      p,
      `${lo.size.toLocaleString("en-US")} → ${hi.size.toLocaleString("en-US")}`,
      ...engines.map((e) => {
        const a = get(e, lo.fixture, "build");
        const b = get(e, hi.fixture, "build");
        if (!a || !b || a.error || b.error) return "—";
        return (
          Math.log(b.median / a.median) / Math.log(hi.size / lo.size)
        ).toFixed(2);
      }),
    ]);
  }
  if (scalingRows.length) {
    out.push("## Scaling exponent", "");
    out.push(
      "Slope of build time over token count on a log-log scale between the smallest and largest size: 1.00 is linear, 2.00 is quadratic.",
      "",
    );
    out.push(
      table(["Profile", "Sizes", ...engines.map(label)], scalingRows),
      "",
    );
  }

  // Updates ------------------------------------------------------------------
  const updateEngines = engines.filter((e) =>
    data.results.some((r) => r.engine === e && r.op === "update"),
  );
  if (updateEngines.length) {
    out.push("## Incremental update: median (ms)", "");
    out.push(
      "Changing the literal token with the most dependents on an already resolved set (`updateToken` in TS, `update-token` in the port). The ratio columns compare the update to a full build of the same engine.",
      "",
    );
    out.push(
      table(
        [
          "Fixture",
          "Tokens",
          "Dependents",
          ...updateEngines.map(label),
          ...updateEngines.map((e) => `${label(e)} update / build`),
        ],
        rows
          .filter((r) => updateEngines.some((e) => get(e, r.fixture, "update")))
          .map((r) => {
            const dep = updateEngines
              .map((e) => get(e, r.fixture, "update"))
              .find((x) => x?.dependents !== undefined)?.dependents;
            const best = bestEngine(updateEngines, get, r.fixture, "update");
            return [
              r.fixture,
              r.size.toLocaleString("en-US"),
              dep?.toLocaleString("en-US") ?? "—",
              ...updateEngines.map((e) =>
                cell(get(e, r.fixture, "update"), "median", e === best),
              ),
              ...updateEngines.map((e) => {
                const u = get(e, r.fixture, "update");
                const b = get(e, r.fixture, "build");
                return u && b && !u.error && !b.error
                  ? `${((u.median / b.median) * 100).toFixed(0)}%`
                  : "—";
              }),
            ];
          }),
      ),
      "",
    );
  }

  // Correctness --------------------------------------------------------------
  out.push("## Resolved / failed tokens", "");
  out.push(
    "Counts from the first build of each engine. ⚠ marks fixtures where the tokenscript engines disagree. StyleDictionary counts a token as failed when its value still contains a reference, and has no tokenscript functions.",
    "",
  );
  const tsEngines = engines.filter((e) => e !== "sd");
  out.push(
    table(
      ["Fixture", "Tokens", ...engines.map(label), "Agree"],
      rows.map((r) => {
        const counts = engines.map((e) => {
          const x = get(e, r.fixture, "build");
          return x && !x.error && x.resolved !== undefined
            ? `${x.resolved} / ${x.errors}`
            : cell(x);
        });
        const tsCounts = tsEngines
          .map((e) => counts[engines.indexOf(e)])
          .filter((c) => c.includes("/"));
        const agree = new Set(tsCounts).size <= 1 ? "✓" : "⚠";
        return [r.fixture, r.size.toLocaleString("en-US"), ...counts, agree];
      }),
    ),
    "",
  );

  const errors = data.results.filter((r) => r.error);
  if (errors.length) {
    out.push("## Errors", "");
    for (const e of errors)
      out.push(
        `- ${label(e.engine)} ${e.fixture} ${e.op}: ${e.error.split("\n")[0]}`,
      );
    out.push("");
  }

  return out.join("\n");
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const data = JSON.parse(readFileSync(process.argv[2], "utf8"));
  const md = render(data);
  if (process.argv[3]) writeFileSync(process.argv[3], md);
  process.stdout.write(`${md}\n`);
}
