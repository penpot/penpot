#!/usr/bin/env node
// Keeps one pull request comment with the coverage report of every project
// (backend, common, frontend, render-wasm). Each test job runs it with its own
// lcov file.
// The comment stores the data of every project in a hidden block, so a run
// updates its own project and renders the whole report again.
//
// Usage:
//   node scripts/coverage-comment.mjs --project <name> --lcov <file>
//        [--prefix <dir/>] [--json-summary <file>] [--emma-xml <file>]
//        [--llvm-json <file>]
//        [--unloaded <file>]
//
//   --prefix        prepended to lcov paths relative to the project directory
//   --json-summary  Istanbul json-summary whose totals replace the lcov ones
//                   (it adds statements)
//   --emma-xml      cloverage EMMA report; its form counts become statements
//   --llvm-json     `cargo llvm-cov --json --summary-only` output whose totals
//                   replace the lcov ones (regions become statements)
//   --unloaded      repo-relative sources no test loads, one per line
//
// Env: GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER and HEAD_SHA; also
// GITHUB_API_URL, GITHUB_SERVER_URL, GITHUB_RUN_ID and GITHUB_RUN_NUMBER,
// which Actions sets.

import fs from "node:fs";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";

import { addTotals, emptyTotals, parseLcov } from "./coverage-lcov.mjs";

const PROJECTS = ["Backend", "Common", "Frontend", "Render WASM"];
const METRICS = [
  ["lines", "Lines"],
  ["statements", "Statements"],
  ["functions", "Functions"],
  ["branches", "Branches"],
];
// Minimum percentage per project and metric; a metric without one gets the
// blue status.
const THRESHOLDS = {
  Backend: { lines: 70 },
  Common: { lines: 70 },
  Frontend: { lines: 50 },
  "Render WASM": { lines: 40 },
};
const MAX_CHANGED_FILES = 100;
const MAX_ATTEMPTS = 3;
// Time to let a concurrent update of another project land before checking
// that this project's data survived it.
const SETTLE_MS = Number(process.env.COVERAGE_COMMENT_SETTLE_MS ?? 10000);

const MARKER = "<!-- coverage-report -->";
const DATA_RE = /<!-- coverage-data ([A-Za-z0-9+/=]+) -->/;

const { values: opts } = parseArgs({
  options: {
    project: { type: "string" },
    lcov: { type: "string" },
    prefix: { type: "string", default: "" },
    "json-summary": { type: "string" },
    "emma-xml": { type: "string" },
    "llvm-json": { type: "string" },
    unloaded: { type: "string" },
  },
});

const env = process.env;
for (const [name, value] of Object.entries({
  "--project": PROJECTS.includes(opts.project),
  "--lcov": opts.lcov,
  GITHUB_TOKEN: env.GITHUB_TOKEN,
  GITHUB_REPOSITORY: env.GITHUB_REPOSITORY,
  PR_NUMBER: env.PR_NUMBER,
  HEAD_SHA: env.HEAD_SHA,
})) {
  if (!value) {
    console.error(
      `Missing or invalid ${name}. Usage: coverage-comment.mjs ` +
        `--project <${PROJECTS.join("|")}> --lcov <file> [--prefix <dir/>] ` +
        "[--json-summary <file>] [--emma-xml <file>] [--llvm-json <file>] " +
        "[--unloaded <file>]",
    );
    process.exit(2);
  }
}

const API = `${env.GITHUB_API_URL ?? "https://api.github.com"}/repos/${env.GITHUB_REPOSITORY}`;
const SERVER = env.GITHUB_SERVER_URL ?? "https://github.com";

class NotFound extends Error {}

async function request(method, url, body) {
  const response = await fetch(url, {
    method,
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${env.GITHUB_TOKEN}`,
      "X-GitHub-Api-Version": "2022-11-28",
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (response.status === 404) throw new NotFound(`${method} ${url}: 404`);
  if (!response.ok) {
    throw new Error(
      `${method} ${url}: ${response.status} ${await response.text()}`,
    );
  }
  return response.status === 204 ? null : response.json();
}

async function getAll(path) {
  const items = [];
  for (let page = 1; ; page++) {
    const batch = await request(
      "GET",
      `${API}${path}?per_page=100&page=${page}`,
    );
    items.push(...batch);
    if (batch.length < 100) return items;
  }
}

// --- Coverage of this project -------------------------------------------

function readList(file) {
  return file && fs.existsSync(file)
    ? fs.readFileSync(file, "utf8").split("\n").filter(Boolean)
    : [];
}

function totalsOf(records) {
  const totals = emptyTotals();
  records.forEach((record) => addTotals(totals, record));
  const result = {};
  for (const [key] of METRICS) {
    if (totals[key]?.total > 0) result[key] = totals[key];
  }
  if (opts["json-summary"]) {
    const summary = JSON.parse(fs.readFileSync(opts["json-summary"], "utf8"));
    for (const [key] of METRICS) {
      const { covered, total } = summary.total[key] ?? {};
      if (total > 0) result[key] = { covered, total };
    }
  }
  if (opts["llvm-json"]) {
    Object.assign(
      result,
      llvmTotals(JSON.parse(fs.readFileSync(opts["llvm-json"], "utf8"))),
    );
  }
  if (opts["emma-xml"]) {
    const forms = emmaForms(fs.readFileSync(opts["emma-xml"], "utf8"));
    if (forms?.total > 0) result.statements = forms;
  }
  return result;
}

// Form counts of the whole report, from the `block, %` entry of the `total`
// element: `<coverage type="block, %" value="67% (1234/5678)"/>`.
function emmaForms(xml) {
  const match = xml.match(
    /<all name="total">[\s\S]*?<coverage type="block, %" value="[^"(]*\((\d+)\/(\d+)\)"/,
  );
  return match ? { covered: Number(match[1]), total: Number(match[2]) } : null;
}

// Totals of an llvm-cov JSON export; regions stand for statements.
function llvmTotals(json) {
  const totals = json.data?.[0]?.totals ?? {};
  const result = {};
  for (const [key, source] of [
    ["lines", "lines"],
    ["statements", "regions"],
    ["functions", "functions"],
    ["branches", "branches"],
  ]) {
    const { count, covered } = totals[source] ?? {};
    if (count > 0) result[key] = { covered, total: count };
  }
  return result;
}

// llvm-cov writes absolute paths; the other tools write paths relative to the
// repository or to the project directory.
function repoPath(lcovPath) {
  if (path.isAbsolute(lcovPath)) return path.relative(process.cwd(), lcovPath);
  return lcovPath.startsWith(opts.prefix) ? lcovPath : opts.prefix + lcovPath;
}

async function projectData() {
  const records = parseLcov(fs.readFileSync(opts.lcov, "utf8"));
  const byPath = new Map(records.map((r) => [repoPath(r.path), r]));
  const unloaded = new Set(readList(opts.unloaded));

  const prFiles = await getAll(`/pulls/${env.PR_NUMBER}/files`);
  const changed = prFiles
    .filter((f) => f.status !== "removed")
    .map((f) => f.filename)
    .filter((file) => byPath.has(file) || unloaded.has(file))
    .sort()
    .map((file) =>
      byPath.has(file)
        ? { file, lines: byPath.get(file).lines }
        : { file, unloaded: true },
    );

  return {
    sha: env.HEAD_SHA,
    runId: Number(env.GITHUB_RUN_ID ?? 0),
    runNumber: env.GITHUB_RUN_NUMBER ?? env.GITHUB_RUN_ID ?? "",
    totals: totalsOf(records),
    changed,
  };
}

// --- Rendering ------------------------------------------------------------

function pct({ covered, total }) {
  return `${Math.round((10000 * covered) / total) / 100}%`;
}

function statusOf(threshold, cov) {
  if (threshold === undefined) return "🔵";
  return (100 * cov.covered) / cov.total >= threshold ? "🟢" : "🔴";
}

function metricRow(threshold, label, cov) {
  const target = threshold === undefined ? "" : ` (🎯 ${threshold}%)`;
  return (
    `<tr> <td align="center">${statusOf(threshold, cov)}</td> <td align="left">${label}</td> ` +
    `<td align="right">${pct(cov)}${target}</td> ` +
    `<td align="right">${cov.covered} / ${cov.total}</td> </tr>`
  );
}

function fileCoverage(changed) {
  if (changed.length === 0) return "No changed files found.";
  const shown = changed.slice(0, MAX_CHANGED_FILES);
  const rows = shown.map(({ file, lines, unloaded }) =>
    unloaded
      ? `<tr> <td align="left"><code>${file}</code></td> <td align="right" colspan="2">no test loads it</td> </tr>`
      : `<tr> <td align="left"><code>${file}</code></td> <td align="right">${lines.total ? pct(lines) : "-"}</td> <td align="right">${lines.covered} / ${lines.total}</td> </tr>`,
  );
  const more =
    changed.length > shown.length
      ? `<p>${changed.length - shown.length} more files not shown.</p>`
      : "";
  return (
    `<table> <thead> <tr> <th align="left">File</th> <th align="right">Lines</th> ` +
    `<th align="right">Covered / Total</th> </tr> </thead> <tbody> ${rows.join(" ")} </tbody> </table>${more}`
  );
}

function renderProject(name, data) {
  if (!data) {
    return `<h3>${name}</h3>\n<p><em>Its tests have not run on this pull request.</em></p>`;
  }
  const rows = METRICS.filter(([key]) => data.totals[key]).map(([key, label]) =>
    metricRow(THRESHOLDS[name]?.[key], label, data.totals[key]),
  );
  const runUrl = `${SERVER}/${env.GITHUB_REPOSITORY}/actions/runs/${data.runId}`;
  const commitUrl = `${SERVER}/${env.GITHUB_REPOSITORY}/commit/${data.sha}`;
  return [
    `<h3>${name}</h3>`,
    `<table> <thead> <tr> <th align="center">Status</th> <th align="left">Category</th> ` +
      `<th align="right">Percentage</th> <th align="right">Covered / Total</th> </tr> </thead> ` +
      `<tbody> ${rows.join(" ")} </tbody> </table>`,
    `<details><summary>File Coverage</summary>${fileCoverage(data.changed)}</details>`,
    `<em>Generated in workflow <a href="${runUrl}">#${data.runNumber}</a> ` +
      `for commit <a href="${commitUrl}">${data.sha.slice(0, 7)}</a></em>`,
  ].join("\n");
}

function render(all) {
  const encoded = Buffer.from(JSON.stringify(all)).toString("base64");
  return [
    MARKER,
    "<h2>Coverage Report</h2>",
    ...PROJECTS.map((name) => renderProject(name, all[name])),
    "<sub>Backend and Common are measured by cloverage, which counts lines " +
      "and Clojure forms (shown as statements) but not functions or branches. " +
      "Render WASM is measured by cargo-llvm-cov on the native test build; " +
      "its statements are llvm regions.</sub>",
    `<!-- coverage-data ${encoded} -->`,
    "",
  ].join("\n\n");
}

// --- Comment update -------------------------------------------------------

function dataOf(comment) {
  const match = comment.body?.match(DATA_RE);
  if (!match) return {};
  try {
    return JSON.parse(Buffer.from(match[1], "base64").toString("utf8"));
  } catch {
    return {};
  }
}

async function reportComments() {
  const comments = await getAll(`/issues/${env.PR_NUMBER}/comments`);
  return comments
    .filter((c) => c.user?.type === "Bot" && c.body?.startsWith(MARKER))
    .sort((a, b) => a.id - b.id);
}

// Merges the data of every report comment, keeping the latest run of each
// project.
function mergeData(comments) {
  const merged = {};
  for (const comment of comments) {
    for (const [name, data] of Object.entries(dataOf(comment))) {
      if (PROJECTS.includes(name) && (merged[name]?.runId ?? -1) < data.runId) {
        merged[name] = data;
      }
    }
  }
  return merged;
}

// Writes the report on the oldest report comment and drops the others, which
// appear when two jobs create the comment at the same time.
async function write(comments, all) {
  const body = render(all);
  if (comments.length === 0) {
    await request("POST", `${API}/issues/${env.PR_NUMBER}/comments`, { body });
    return;
  }
  const [primary, ...extra] = comments;
  await request("PATCH", `${API}/issues/comments/${primary.id}`, { body });
  for (const comment of extra) {
    // Another job may have dropped it already.
    await request("DELETE", `${API}/issues/comments/${comment.id}`).catch(
      (error) => {
        if (!(error instanceof NotFound)) throw error;
      },
    );
  }
}

const mine = await projectData();

for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
  const comments = await reportComments();
  const all = mergeData(comments);
  if ((all[opts.project]?.runId ?? -1) > mine.runId) {
    console.log(`A newer run already reported ${opts.project}.`);
    break;
  }
  all[opts.project] = mine;
  try {
    await write(comments, all);
  } catch (error) {
    // The comment vanished under a concurrent update; the check below retries.
    if (!(error instanceof NotFound)) throw error;
  }

  await sleep(SETTLE_MS * (1 + Math.random() / 2));
  const after = await reportComments();
  const saved = mergeData(after)[opts.project];
  if (after.length === 1 && (saved?.runId ?? -1) >= mine.runId) {
    console.log(`Coverage report updated for ${opts.project}.`);
    break;
  }
  if (attempt === MAX_ATTEMPTS) {
    throw new Error(`Could not keep the ${opts.project} coverage report.`);
  }
  console.log(`Concurrent update detected; writing ${opts.project} again.`);
}
