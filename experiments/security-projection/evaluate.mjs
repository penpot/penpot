#!/usr/bin/env node
// Evaluate decision models against the labeled projection set.
//
// Reads fixtures/labeled-projections.json, asks every model the same questions
// about each projection, and reports precision / recall / false positives /
// false negatives, plus cost and latency. Entries tagged "ambiguous" are shown
// but excluded from the metrics; entries tagged "adversarial" stay in the
// metrics and can be read apart in the per-case table.
//
// Usage:
//   node evaluate.mjs                          # default models, threshold 0.5
//   node evaluate.mjs --threshold 0.7
//   node evaluate.mjs typesafe/jev-1.13 cloudflare/clef-flash
//
// Config comes from .env, same as analyze.mjs. Needs no Penpot instance: the
// fixtures carry the projections.

import fs from "node:fs";
import process from "node:process";
import { pathToFileURL } from "node:url";
import { askDecisions, deterministicChecks } from "./analyze.mjs";

const FIXTURES = new URL("./fixtures/labeled-projections.json", import.meta.url);

const DEFAULT_MODELS = [
  "typesafe/jev-1.13",
  "cloudflare/clef-flash",
  "liquid/d1",
  "perplexity/pplx-decider-v1.1-27b",
  "inception/mercury-decide:free",
];

function parseArgs(argv) {
  const args = { threshold: 0.5, models: [] };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--threshold") args.threshold = Number(argv[++i]);
    else args.models.push(argv[i]);
  }
  return args;
}

export function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
}

export function summarize(rows, threshold) {
  let tp = 0, fp = 0, fn = 0, tn = 0;
  for (const row of rows) {
    const predicted = row.prob >= threshold;
    const actual = row.label === "phishing";
    if (predicted && actual) tp++;
    else if (predicted && !actual) fp++;
    else if (!predicted && actual) fn++;
    else tn++;
  }
  const precision = tp + fp ? tp / (tp + fp) : null;
  const recall = tp + fn ? tp / (tp + fn) : null;
  const f1 = precision && recall ? (2 * precision * recall) / (precision + recall) : null;
  return { tp, fp, fn, tn, precision, recall, f1 };
}

const fmt = (value, digits = 3) =>
  value === null || value === undefined ? "-" : value.toFixed(digits);

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const models = args.models.length ? args.models : DEFAULT_MODELS;

  const { entries } = JSON.parse(fs.readFileSync(FIXTURES, "utf8"));
  const scored = entries.filter((e) => e.tag !== "ambiguous");
  const benign = entries.filter((e) => e.label === "benign").length;

  console.log(`entries: ${entries.length} (benign ${benign}, phishing ${entries.length - benign})`);
  console.log(`ambiguous (excluded from metrics): ${entries.length - scored.length}`);
  console.log(`threshold: ${args.threshold}`);
  console.log(`models: ${models.join(", ")}\n`);

  // results[model][entryId] = { prob, ms, cost } | { error }
  const results = {};
  for (const model of models) {
    results[model] = {};
    for (const entry of entries) {
      const started = Date.now();
      try {
        const body = await askDecisions(entry.projection, deterministicChecks(entry.projection), model);
        results[model][entry.id] = {
          prob: body.answers?.is_phishing?.noul ?? null,
          ms: Date.now() - started,
          cost: body.usage?.cost ?? 0,
        };
      } catch (err) {
        results[model][entry.id] = { error: err.message };
      }
    }
    process.stderr.write(`  ${model} done\n`);
  }

  console.log("model\tthr\tTP\tFP\tFN\tTN\tprecision\trecall\tf1\tp50ms\tcost$");
  for (const model of models) {
    const rows = scored
      .map((entry) => ({ label: entry.label, prob: results[model][entry.id]?.prob }))
      .filter((row) => typeof row.prob === "number");
    const m = summarize(rows, args.threshold);
    const times = Object.values(results[model]).map((r) => r.ms).filter(Boolean);
    const cost = Object.values(results[model]).reduce((acc, r) => acc + (r.cost ?? 0), 0);
    console.log([
      model,
      args.threshold,
      m.tp, m.fp, m.fn, m.tn,
      fmt(m.precision, 2), fmt(m.recall, 2), fmt(m.f1, 2),
      median(times) ?? "-",
      cost.toFixed(6),
    ].join("\t"));
  }

  const errors = models.flatMap((model) =>
    Object.entries(results[model]).filter(([, r]) => r.error).map(([id, r]) => `${model} ${id}: ${r.error}`));
  if (errors.length) {
    console.log("\nerrors:");
    for (const line of errors) console.log(`  ${line}`);
  }

  console.log("\nper-case is_phishing probability:");
  console.log(["case", "label", "tag", ...models].join("\t"));
  for (const entry of entries) {
    const cells = models.map((model) => fmt(results[model][entry.id]?.prob));
    console.log([entry.id, entry.label, entry.tag, ...cells].join("\t"));
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(`error: ${err.stack ?? String(err)}`);
    process.exit(1);
  });
}
