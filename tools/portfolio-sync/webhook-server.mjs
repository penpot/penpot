#!/usr/bin/env node
/**
 * webhook-server.mjs
 *
 * Listens for Vercel deploy and GitHub push webhooks, then enqueues a run of
 * screenshot-pipeline.mjs for the appropriate URL.
 *
 * Expose publicly with:
 *   cloudflared tunnel --url http://localhost:9090
 *
 * Config is re-read from portfolio-sync.config.json on every request so you
 * can change secrets / URLs without restarting the server.
 */

import http from "node:http";
import crypto from "node:crypto";
import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, "portfolio-sync.config.json");
const PIPELINE = path.join(__dirname, "screenshot-pipeline.mjs");

// ─── logging ────────────────────────────────────────────────────────────────

function ts() {
  return new Date().toTimeString().slice(0, 8);
}

function log(msg) {
  console.log(`[webhook-server] ${ts()} ${msg}`);
}

// ─── config ─────────────────────────────────────────────────────────────────

function readConfig() {
  try {
    return JSON.parse(readFileSync(CONFIG_PATH, "utf8"));
  } catch (err) {
    log(`WARN: could not read config (${err.message}); using empty defaults`);
    return {};
  }
}

// ─── queue ───────────────────────────────────────────────────────────────────

/**
 * Each entry: { source: "vercel"|"github", url: string, id: string }
 * id = source + ":" + url — used to deduplicate.
 */
const queue = [];
let running = null; // currently-running entry or null

function enqueue(source, url) {
  const id = `${source}:${url}`;
  if (queue.some((e) => e.id === id) || (running && running.id === id)) {
    log(`SKIP duplicate entry ${id}`);
    return;
  }
  queue.push({ source, url, id });
  log(`QUEUED [${source}] ${url}  (queue depth: ${queue.length})`);
  processNext();
}

function processNext() {
  if (running || queue.length === 0) return;
  const entry = queue.shift();
  running = entry;
  log(`START  [${entry.source}] ${entry.url}`);

  const child = spawn(
    process.execPath,
    [PIPELINE, "--base", entry.url],
    { stdio: "inherit" }
  );

  child.on("exit", (code) => {
    log(`DONE   [${entry.source}] ${entry.url}  exit=${code}`);
    running = null;
    processNext();
  });

  child.on("error", (err) => {
    log(`ERROR  [${entry.source}] ${entry.url}  ${err.message}`);
    running = null;
    processNext();
  });
}

// ─── HMAC helpers ────────────────────────────────────────────────────────────

function hmacSHA1(secret, data) {
  return "sha1=" + crypto.createHmac("sha1", secret).update(data).digest("hex");
}

function hmacSHA256(secret, data) {
  return (
    "sha256=" +
    crypto.createHmac("sha256", secret).update(data).digest("hex")
  );
}

function safeEqual(a, b) {
  try {
    return crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
  } catch {
    return false;
  }
}

// ─── body reader ─────────────────────────────────────────────────────────────

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

// ─── route helpers ───────────────────────────────────────────────────────────

function send(res, status, body, contentType = "text/plain") {
  const payload =
    typeof body === "string" ? body : JSON.stringify(body, null, 2);
  res.writeHead(status, {
    "Content-Type":
      typeof body === "string" ? contentType : "application/json",
    "Content-Length": Buffer.byteLength(payload),
  });
  res.end(payload);
}

// ─── handlers ────────────────────────────────────────────────────────────────

async function handleVercel(req, res, cfg) {
  const rawBody = await readBody(req);

  // Signature verification
  if (cfg.vercel_webhook_secret) {
    const sig = req.headers["x-vercel-signature"] || "";
    const expected = hmacSHA1(cfg.vercel_webhook_secret, rawBody);
    if (!safeEqual(sig, expected)) {
      log("VERCEL bad signature — rejected");
      return send(res, 401, "Unauthorized");
    }
  }

  let payload;
  try {
    payload = JSON.parse(rawBody.toString("utf8"));
  } catch {
    return send(res, 400, "Bad JSON");
  }

  const type = (payload.type || "").toLowerCase();
  if (!type.includes("succeeded") && !type.includes("ready")) {
    log(`VERCEL ignoring type="${payload.type}"`);
    return send(res, 200, "ignored");
  }

  // Extract URL from payload
  let deployUrl =
    (payload.payload && payload.payload.deployment && payload.payload.deployment.url) ||
    payload.url ||
    "";

  if (deployUrl && !deployUrl.startsWith("http")) {
    deployUrl = "https://" + deployUrl;
  }
  if (!deployUrl) {
    deployUrl = cfg.vercel_url || "";
  }

  if (!deployUrl) {
    log("VERCEL no URL found — cannot enqueue");
    return send(res, 422, "no deployment URL");
  }

  log(`VERCEL deploy event type="${payload.type}" url=${deployUrl}`);
  enqueue("vercel", deployUrl);
  return send(res, 200, "queued");
}

async function handleGitHub(req, res, cfg) {
  const rawBody = await readBody(req);

  // Signature verification
  if (cfg.github_webhook_secret) {
    const sig = req.headers["x-hub-signature-256"] || "";
    const expected = hmacSHA256(cfg.github_webhook_secret, rawBody);
    if (!safeEqual(sig, expected)) {
      log("GITHUB bad signature — rejected");
      return send(res, 401, "Unauthorized");
    }
  }

  const event = req.headers["x-github-event"] || "";
  if (event !== "push") {
    log(`GITHUB ignoring event="${event}"`);
    return send(res, 200, "ignored");
  }

  let payload;
  try {
    payload = JSON.parse(rawBody.toString("utf8"));
  } catch {
    return send(res, 400, "Bad JSON");
  }

  const ref = payload.ref || "";
  if (ref !== "refs/heads/main" && ref !== "refs/heads/master") {
    log(`GITHUB ignoring push to ref="${ref}"`);
    return send(res, 200, "ignored");
  }

  const targetUrl = cfg.portfolio_local || "http://localhost:4321";
  log(`GITHUB push to ${ref} — using local URL ${targetUrl}`);
  enqueue("github", targetUrl);
  return send(res, 200, "queued");
}

// ─── server ──────────────────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  const cfg = readConfig();
  const { method, url } = req;

  try {
    if (method === "POST" && url === "/vercel") {
      return await handleVercel(req, res, cfg);
    }

    if (method === "POST" && url === "/github") {
      return await handleGitHub(req, res, cfg);
    }

    if (method === "GET" && url === "/status") {
      return send(res, 200, {
        queue: queue.map((e) => ({ source: e.source, url: e.url })),
        running: running ? { source: running.source, url: running.url } : null,
        config: cfg,
      });
    }

    if (method === "GET" && (url === "/" || url === "")) {
      return send(
        res,
        200,
        [
          "webhook-server — portfolio sync pipeline trigger",
          "",
          "Endpoints:",
          "  POST /vercel   — Vercel deploy-succeeded webhook",
          "  POST /github   — GitHub push webhook (main/master only)",
          "  GET  /status   — queue + running state + loaded config",
          "  GET  /         — this help text",
          "",
          "Expose publicly:",
          "  cloudflared tunnel --url http://localhost:9090",
          "",
          `Config: ${CONFIG_PATH}`,
          `Pipeline: ${PIPELINE}`,
        ].join("\n")
      );
    }

    send(res, 404, "Not found");
  } catch (err) {
    log(`UNHANDLED ERROR: ${err.message}`);
    send(res, 500, "Internal server error");
  }
});

const port = (() => {
  try {
    return readConfig().webhook_port || 9090;
  } catch {
    return 9090;
  }
})();

server.listen(port, "127.0.0.1", () => {
  log(`Listening on http://localhost:${port}`);
  log("");
  log("Endpoints:");
  log("  POST /vercel   — Vercel deploy-succeeded webhook");
  log("  POST /github   — GitHub push webhook (main/master only)");
  log("  GET  /status   — queue + running state + loaded config");
  log("  GET  /         — usage info");
  log("");
  log("Expose with: cloudflared tunnel --url http://localhost:9090");
  log("");
  log(`Config file: ${CONFIG_PATH}`);
  log("(config is re-read on each request — no restart needed after edits)");
});
