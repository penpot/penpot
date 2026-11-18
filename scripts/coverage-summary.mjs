#!/usr/bin/env node
// Prints a markdown coverage summary of an lcov file, grouped by source area.
//
// Usage:
//   node scripts/coverage-summary.mjs --title <title> --lcov <file>
//        [--note <text>] [--unloaded <file>]
//
// `--unloaded` names a file listing, one per line, the sources no test loaded;
// they are shown apart and never counted. Meant to be appended to
// `$GITHUB_STEP_SUMMARY`.

import fs from "node:fs";
import { parseArgs } from "node:util";

import { addTotals, emptyTotals, parseLcov } from "./coverage-lcov.mjs";

const { values: opts } = parseArgs({
  options: {
    title: { type: "string" },
    lcov: { type: "string" },
    note: { type: "string" },
    unloaded: { type: "string" },
  },
});

if (!opts.title || !opts.lcov) {
  console.error(
    "Usage: coverage-summary.mjs --title <title> --lcov <file> [--note <text>] [--unloaded <file>]",
  );
  process.exit(2);
}

// Path from `app/` on: `app/main/<dir>` and `app/common/<dir>` group one
// level deeper than the rest, which group by `app/<dir>`.
function areaOf(filePath) {
  const parts = filePath.replace(/^.*?\bsrc\//, "").split("/");
  const depth =
    ["main", "common"].includes(parts[1]) && parts.length > 3 ? 3 : 2;
  return parts.slice(0, Math.min(depth, parts.length - 1)).join("/") || "src";
}

function percent({ covered, total }) {
  return total === 0 ? "-" : `${((100 * covered) / total).toFixed(1)}%`;
}

function row(name, t, metrics) {
  const cells = metrics.map((key) => percent(t[key]));
  return `| ${name} | ${t.files} | ${cells.join(" | ")} |`;
}

const records = parseLcov(fs.readFileSync(opts.lcov, "utf8"));
const areas = new Map();
const total = emptyTotals();
for (const record of records) {
  const area = areaOf(record.path);
  if (!areas.has(area)) areas.set(area, emptyTotals());
  addTotals(areas.get(area), record);
  addTotals(total, record);
}

// Some tools (cloverage) report lines only; metrics with no data are left out.
const metrics = ["lines", "functions", "branches"].filter(
  (key) => total[key].total > 0,
);
const headers = {
  lines: "Lines",
  functions: "Functions",
  branches: "Branches",
};

const unloaded =
  opts.unloaded && fs.existsSync(opts.unloaded)
    ? fs.readFileSync(opts.unloaded, "utf8").split("\n").filter(Boolean)
    : [];

const out = [`## ${opts.title}`, ""];
if (opts.note) out.push(opts.note, "");
out.push(
  `**${percent(total.lines)}** of lines covered (${total.lines.covered} of ${total.lines.total}).`,
  "",
  `| Area | Files | ${metrics.map((key) => headers[key]).join(" | ")} |`,
  `| --- | ---: | ${metrics.map(() => "---:").join(" | ")} |`,
  ...[...areas.keys()]
    .sort()
    .map((area) => row(area, areas.get(area), metrics)),
  row("**Total**", total, metrics),
  "",
);
if (unloaded.length > 0) {
  out.push(
    `<details><summary>${unloaded.length} sources no test loads (not counted above)</summary>`,
    "",
    ...unloaded.map((file) => `- \`${file}\``),
    "",
    "</details>",
    "",
  );
}
console.log(out.join("\n"));
