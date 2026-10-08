#!/usr/bin/env node
// Security-projection prototype for Penpot.
//
// Pipeline:
//   Penpot file -> get-semantic-file RPC -> security projection
//               -> deterministic checks (code, cheap, authoritative)
//               -> decision model via OpenRouter (judgment on the ambiguous)
//               -> structured decision JSON
//
// The point of the prototype is to measure whether a small semantic
// projection is enough for a decision model to judge phishing, without
// sending the raw (and huge) Penpot file.
//
// Usage:
//   node analyze.mjs <file-id>                  # reads config from ./.env
//   node analyze.mjs <file-id> --dump           # only the projection + checks
//   node analyze.mjs <file-id> --request        # print the decisions request, do not send it
//   node analyze.mjs <file-id> --model <name>   # override the model
//   node analyze.mjs <file-id> --env <path>     # use another .env file
//
// .env (real environment variables win over the file):
//   PENPOT_BASE_URL            default http://localhost:3450
//   PENPOT_EMAIL               login email (unless PENPOT_ACCESS_TOKEN is set)
//   PENPOT_PASSWORD            login password
//   PENPOT_ACCESS_TOKEN        optional; skips the login round-trip
//   OPENROUTER_API_KEY         required for the model call
//   OPENROUTER_MODEL           default model id
//   OPENROUTER_DECISIONS_URL   default https://openrouter.ai/api/alpha/decisions

import process from "node:process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import dotenv from "dotenv";

// ---------------------------------------------------------------------------
// Configuration
//
// Config and secrets (Penpot URL, credentials, OpenRouter key/model) come from
// a .env file. Real environment variables win over the file. The operational
// inputs (file id, --dump, --model) stay as CLI arguments.
// ---------------------------------------------------------------------------

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));

function loadEnvFile() {
  const argv = process.argv.slice(2);
  const idx = argv.indexOf("--env");
  const candidates = idx !== -1 && argv[idx + 1]
    ? [resolve(argv[idx + 1])]
    : [resolve(process.cwd(), ".env"), resolve(SCRIPT_DIR, ".env")];

  for (const path of candidates) {
    if (existsSync(path)) {
      dotenv.config({ path, quiet: true });
      return path;
    }
  }
  return null;
}

const ENV_FILE = loadEnvFile();
const ENV = process.env;
const BASE_URL = (ENV.PENPOT_BASE_URL ?? "http://localhost:3450").replace(/\/$/, "");
const COOKIE_NAME = ENV.PENPOT_COOKIE_NAME ?? "auth-token";

// ---------------------------------------------------------------------------
// Penpot RPC
// ---------------------------------------------------------------------------

export async function login() {
  const { PENPOT_EMAIL: email, PENPOT_PASSWORD: password } = ENV;
  if (!email || !password) {
    fail("set PENPOT_EMAIL and PENPOT_PASSWORD (or PENPOT_ACCESS_TOKEN)");
  }

  const res = await fetch(`${BASE_URL}/api/main/methods/login-with-password`, {
    method: "POST",
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify({ email, password }),
  });

  if (!res.ok) {
    fail(`login failed (${res.status}): ${await safeText(res)}`);
  }

  const setCookie = res.headers.get("set-cookie") ?? "";
  const match = setCookie.match(new RegExp(`${COOKIE_NAME}=([^;]+)`));
  if (!match) {
    fail(`login succeeded but no ${COOKIE_NAME} cookie was returned`);
  }
  return match[1];
}

export async function rpc(method, params, auth) {
  const headers = {
    "content-type": "application/json",
    accept: "application/json",
  };
  if (auth.token) {
    headers.authorization = `Token ${auth.token}`;
  } else {
    headers.cookie = `${COOKIE_NAME}=${auth.cookie}`;
  }

  const res = await fetch(`${BASE_URL}/api/main/methods/${method}`, {
    method: "POST",
    headers,
    body: JSON.stringify(params),
  });

  if (!res.ok) {
    fail(`RPC ${method} failed (${res.status}): ${await safeText(res)}`);
  }

  // Some commands (e.g. delete-file) answer 204 with no body.
  const body = await res.text();
  return body ? JSON.parse(body) : null;
}

export async function resolveAuth() {
  return ENV.PENPOT_ACCESS_TOKEN
    ? { token: ENV.PENPOT_ACCESS_TOKEN }
    : { cookie: await login() };
}

export async function safeText(res) {
  try {
    return (await res.text()).slice(0, 500);
  } catch {
    return res.statusText;
  }
}

function fail(message) {
  console.error(`error: ${message}`);
  process.exit(1);
}

// ---------------------------------------------------------------------------
// Deterministic checks: cheap, explainable, and not something a model should
// be trusted to compute. They run on the projection, never on the raw file.
// ---------------------------------------------------------------------------

const BRAND_TOKENS = [
  "paypal", "apple", "icloud", "google", "gmail", "microsoft", "outlook",
  "amazon", "netflix", "facebook", "instagram", "whatsapp", "spotify",
  "binance", "coinbase", "metamask", "dhl", "fedex", "ups", "santander",
  "bbva", "caixabank", "bankinter", "hsbc", "chase", "wellsfargo",
];

const SUSPICIOUS_TLDS = new Set([
  "zip", "mov", "top", "xyz", "click", "link", "work", "gq", "cf", "tk",
  "ml", "rest", "buzz", "country", "kim", "loan", "review",
]);

const CREDENTIAL_WORDS = [
  "password", "passcode", "contraseña", "otp", "one-time", "verification code",
  "security code", "cvv", "card number", "credit card", "social security",
  "ssn", "pin ", "seed phrase", "recovery phrase",
];

const URGENCY_WORDS = [
  "urgent", "immediately", "24 hours", "48 hours", "suspend", "suspended",
  "blocked", "locked", "verify now", "act now", "final warning", "last chance",
  "expires", "unusual activity", "unauthorized",
];

const PAYMENT_WORDS = [
  "payment", "billing", "invoice", "refund", "transfer", "wire", "crypto",
  "wallet", "iban", "credit card",
];

export function projectionText(projection) {
  const parts = [];
  for (const page of projection.pages) {
    parts.push(page.name);
    parts.push(...(page.text ?? []));
    for (const frame of page.frames) {
      parts.push(frame.name);
      parts.push(...(frame.text ?? []));
    }
  }
  return parts.join("\n").toLowerCase();
}

export function allLinks(projection) {
  const links = [];
  for (const page of projection.pages) {
    links.push(...(page.links ?? []));
    for (const frame of page.frames) links.push(...(frame.links ?? []));
  }
  return links;
}

function hostOf(url) {
  try {
    return new URL(url).hostname.toLowerCase();
  } catch {
    return null;
  }
}

export function scoreUrl(url) {
  const reasons = [];
  let score = 0;
  const host = hostOf(url);
  if (!host) return { score: 0, reasons: ["unparseable url"] };

  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(host)) {
    score += 2;
    reasons.push("host is a raw IP address");
  }
  if (host.includes("xn--")) {
    score += 3;
    reasons.push("punycode host (homograph risk)");
  }
  if ((host.match(/\./g) ?? []).length >= 4) {
    score += 1;
    reasons.push("deeply nested subdomains");
  }
  const tld = host.split(".").pop();
  if (SUSPICIOUS_TLDS.has(tld)) {
    score += 1;
    reasons.push(`suspicious TLD .${tld}`);
  }
  for (const brand of BRAND_TOKENS) {
    if (host.includes(brand) && !host.endsWith(`${brand}.com`)) {
      score += 2;
      reasons.push(`brand token "${brand}" outside its official domain`);
      break;
    }
  }
  if (/@/.test(url)) {
    score += 3;
    reasons.push("userinfo/@ in the authority");
  }
  if (/(login|signin|sign-in|verify|secure|account|update|confirm|recovery)/i.test(url)) {
    score += 1;
    reasons.push("credential-themed path");
  }
  return { score, reasons };
}

export function deterministicChecks(projection) {
  const text = projectionText(projection);
  const links = allLinks(projection);

  const matched = (words) => words.filter((w) => text.includes(w));
  const urls = links.map((link) => ({ ...link, ...scoreUrl(link.url) }));

  return {
    requests_credentials: matched(CREDENTIAL_WORDS),
    uses_urgency: matched(URGENCY_WORDS),
    requests_payment: matched(PAYMENT_WORDS),
    brands_mentioned: matched(BRAND_TOKENS),
    links: urls,
    suspicious_urls: urls.filter((u) => u.score >= 2),
  };
}

// ---------------------------------------------------------------------------
// Decision model via OpenRouter
//
// Decision models (Jev, Solar Decide, d1, ...) speak the Decisions API:
// POST /api/alpha/decisions with { model, state, questions }, and return typed
// probabilities instead of generated text.
// ---------------------------------------------------------------------------

export const DECISION_QUESTIONS = {
  is_phishing: {
    type: "noul",
    instructions: "Is this design a phishing attempt: a fake page that impersonates a trusted service to steal credentials or money?",
    criteria: {
      true: "The design is a phishing attempt.",
      false: "The design is legitimate or benign.",
    },
  },
  requests_credentials: {
    type: "noul",
    instructions: "Does the design ask the user for credentials such as a password, passcode, card number, or recovery phrase?",
    criteria: {
      true: "It requests credentials.",
      false: "It does not request credentials.",
    },
  },
  requests_payment: {
    type: "noul",
    instructions: "Does the design ask the user to pay, transfer money, or enter payment details?",
    criteria: {
      true: "It requests a payment.",
      false: "It does not request a payment.",
    },
  },
  requests_otp: {
    type: "noul",
    instructions: "Does the design ask for a one-time code, OTP, or verification code?",
    criteria: {
      true: "It requests a one-time code.",
      false: "It does not request a one-time code.",
    },
  },
  impersonates_brand: {
    type: "noul",
    instructions: "Does the design impersonate a known brand or service through its names, logos, or copy?",
    criteria: {
      true: "It mimics a specific brand or service.",
      false: "It does not mimic a brand.",
    },
  },
  contains_suspicious_urls: {
    type: "noul",
    instructions: "Do any links point to a suspicious or deceptive URL, such as a raw IP host, a punycode host, a brand name outside its official domain, or a suspicious TLD?",
    criteria: {
      true: "At least one link is suspicious.",
      false: "The links look ordinary.",
    },
  },
  uses_urgency: {
    type: "noul",
    instructions: "Does the design use urgency or fear to pressure the user, such as an account suspension, a deadline, or an unusual-activity warning?",
    criteria: {
      true: "It pressures the user with urgency or fear.",
      false: "It does not use urgency.",
    },
  },
  contains_social_engineering: {
    type: "noul",
    instructions: "Does the design use social-engineering tactics beyond plain urgency, such as authority, a reward, scarcity, or a threat?",
    criteria: {
      true: "It uses social engineering.",
      false: "It does not use social engineering.",
    },
  },
  external_login: {
    type: "noul",
    instructions: "Does the design send the user to an external site to log in?",
    criteria: {
      true: "It redirects to an external login.",
      false: "It does not redirect to an external login.",
    },
  },
  is_malicious: {
    type: "noul",
    instructions: "Is the design malicious: phishing, fraud, malware distribution, or another harmful intent?",
    criteria: {
      true: "The design is malicious.",
      false: "The design is not malicious.",
    },
  },
};

export function decisionState(projection, checks) {
  return { design: projection, deterministic_checks: checks };
}

export async function askDecisions(projection, checks, model) {
  const url = ENV.OPENROUTER_DECISIONS_URL ?? "https://openrouter.ai/api/alpha/decisions";
  const res = await fetch(url, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Bearer ${ENV.OPENROUTER_API_KEY}`,
    },
    body: JSON.stringify(buildDecisionsRequest(model, projection, checks)),
  });

  if (!res.ok) {
    throw new Error(`OpenRouter decisions failed (${res.status}): ${await safeText(res)}`);
  }
  return res.json();
}

export function buildDecisionsRequest(model, projection, checks) {
  return {
    model,
    state: decisionState(projection, checks),
    questions: DECISION_QUESTIONS,
  };
}

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

function parseArgs(argv) {
  const args = { fileId: null, dump: false, request: false, model: ENV.OPENROUTER_MODEL };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--dump") args.dump = true;
    else if (arg === "--request") args.request = true;
    else if (arg === "--model") args.model = argv[++i];
    else if (arg === "--env") i++;
    else if (!args.fileId) args.fileId = arg;
  }
  return args;
}

function approxTokens(obj) {
  return Math.ceil(JSON.stringify(obj).length / 4);
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.fileId) fail("usage: node analyze.mjs <file-id> [--dump] [--request] [--model <name>] [--env <path>]");
  if (ENV_FILE) console.error(`config: ${ENV_FILE}`);

  const auth = await resolveAuth();

  const projection = await rpc("get-semantic-file", { id: args.fileId }, auth);
  const checks = deterministicChecks(projection);

  console.log("=== projection ===");
  console.log(JSON.stringify(projection, null, 2));
  console.log(`\nprojection size: ${JSON.stringify(projection).length} chars (~${approxTokens(projection)} tokens)`);

  console.log("\n=== deterministic checks ===");
  console.log(JSON.stringify(checks, null, 2));

  if (args.dump) return;

  if (!args.model) fail("set OPENROUTER_MODEL or pass --model <name>");
  const request = buildDecisionsRequest(args.model, projection, checks);

  if (args.request) {
    console.log(`\n=== request (POST ${ENV.OPENROUTER_DECISIONS_URL ?? "https://openrouter.ai/api/alpha/decisions"}) ===`);
    console.log(JSON.stringify(request, null, 2));
    console.log(`\nrequest size: ${JSON.stringify(request).length} chars (~${approxTokens(request)} tokens)`);
    return;
  }

  if (!ENV.OPENROUTER_API_KEY) fail("set OPENROUTER_API_KEY (or use --dump / --request)");

  console.log(`\n=== model: ${args.model} ===`);
  const started = Date.now();
  const body = await askDecisions(projection, checks, args.model);
  const elapsed = Date.now() - started;

  console.log(JSON.stringify(body.answers, null, 2));
  console.log(`\nmodel: ${body.model ?? args.model} · provider: ${body.provider ?? "?"}`);
  console.log(`elapsed: ${elapsed} ms`);
  if (body.usage) console.log(`usage: ${JSON.stringify(body.usage)}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((err) => fail(err.stack ?? String(err)));
}
