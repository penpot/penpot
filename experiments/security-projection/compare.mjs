#!/usr/bin/env node
// Compare decision models on the same request.
//
// Fetches the security projection for one file once, builds the same decisions
// request, and sends it to every model so the answers, latency and cost can be
// compared side by side. The skill's guidance: never pick a decision model from
// memory, probe it on your own inputs.
//
// Usage:
//   node compare.mjs <file-id>                 # the default shortlist
//   node compare.mjs <file-id> <model> [...]   # explicit models
//
// Config comes from .env, same as analyze.mjs.

import process from "node:process";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  askDecisions,
  deterministicChecks,
  resolveAuth,
  rpc,
} from "./analyze.mjs";

const DEFAULT_MODELS = [
  "typesafe/jev-1.13",
  "cloudflare/clef-flash",
  "liquid/d1",
  "perplexity/pplx-decider-v1.1-27b",
  "openai/gpt-6-luna-decisions",
  "upstage/solar-decide-flash",
  "inception/mercury-decide:free",
];

function fmt(value) {
  return value === undefined || value === null ? "  -  " : value.toFixed(3);
}

async function main() {
  const [fileId, ...models] = process.argv.slice(2);
  if (!fileId) {
    console.error("usage: node compare.mjs <file-id> [model ...]");
    process.exit(1);
  }

  const auth = await resolveAuth();
  const projection = await rpc("get-semantic-file", { id: fileId }, auth);
  const checks = deterministicChecks(projection);

  console.log(`file: ${projection.fileName} (${fileId})`);
  console.log(`projection: ${JSON.stringify(projection).length} chars`);
  console.log(`models: ${(models.length ? models : DEFAULT_MODELS).join(", ")}\n`);

  const header = ["model", "ms", "cost$", "is_phish", "creds", "payment", "susp"];
  console.log(header.join("\t"));

  for (const model of (models.length ? models : DEFAULT_MODELS)) {
    const started = Date.now();
    try {
      const body = await askDecisions(projection, checks, model);
      const ms = Date.now() - started;
      const a = body.answers ?? {};
      console.log([
        model,
        ms,
        body.usage?.cost !== undefined ? body.usage.cost.toFixed(6) : "-",
        fmt(a.is_phishing?.noul),
        fmt(a.requests_credentials?.noul),
        fmt(a.requests_payment?.noul),
        fmt(a.contains_suspicious_urls?.noul),
      ].join("\t"));
    } catch (err) {
      console.log([model, Date.now() - started, "-", "ERROR", err.message.slice(0, 60)].join("\t"));
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => {
    console.error(`error: ${err.stack ?? String(err)}`);
    process.exit(1);
  });
}
