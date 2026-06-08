#!/usr/bin/env node
/**
 * portfolio-watcher.mjs
 *
 * Watches the portfolio's src/ directory and re-runs the screenshot pipeline
 * whenever files change.
 *
 * - Reads portfolio_dir + portfolio_local + pipeline_mode from
 *   portfolio-sync.config.json so the toolkit is portable across machines.
 * - pipeline_mode = "live-dom" (default) → spawns build-live-dom-canvas.mjs;
 *   editable text + hyperlinks land on the canvas.
 * - pipeline_mode = "screenshot" → spawns screenshot-pipeline.mjs --base ...;
 *   the prior behaviour, drops PNG rects on the canvas.
 * - Debounces 2s after the last change event.
 * - Health-checks the REPL (port 4403) and the dev server before triggering.
 * - Prevents parallel pipeline runs (queues one retry if busy).
 *
 * Usage:
 *   node ./portfolio-watcher.mjs
 */

import fs from 'fs';
import path from 'path';
import { spawn } from 'child_process';
import { fileURLToPath } from 'url';

// ── Config ────────────────────────────────────────────────────────────────────

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, 'portfolio-sync.config.json');

const PIPELINE_CHOICES = {
  'live-dom':   { path: path.join(__dirname, 'build-live-dom-canvas.mjs'), label: 'live-DOM canvas build' },
  'screenshot': { path: path.join(__dirname, 'screenshot-pipeline.mjs'),    label: 'screenshot pipeline' },
};

function readConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch (err) {
    console.error(`[portfolio-watcher] could not read ${CONFIG_PATH}: ${err.message}`);
    process.exit(1);
  }
}

const cfg = readConfig();
const PORTFOLIO_DIR = cfg.portfolio_dir;
const WATCH_DIR     = path.join(PORTFOLIO_DIR, 'src');
const PORTFOLIO_URL = cfg.portfolio_local || 'http://localhost:4321';
// Pipeline mode is overridable via config — defaults to live-dom so saves don't
// replace editable shapes with flat screenshots. Set "pipeline_mode": "screenshot"
// in the config to restore the prior PNG-import behaviour.
const PIPELINE_MODE = cfg.pipeline_mode || 'live-dom';
const PIPELINE = PIPELINE_CHOICES[PIPELINE_MODE] || PIPELINE_CHOICES['live-dom'];
const PIPELINE_SCRIPT = PIPELINE.path;
const REPL_URL      = 'http://localhost:4403/execute';
const DEBOUNCE_MS   = 2_000;

// Files / names to ignore
const SKIP_NAMES  = new Set(['.DS_Store']);
const SKIP_SUFFIX = '~';
const SKIP_PREFIX = '.';

// ── State ─────────────────────────────────────────────────────────────────────

let debounceTimer   = null;
let pipelineRunning = false;
let retryQueued     = false;

// ── Logging ───────────────────────────────────────────────────────────────────

function ts() {
  return new Date().toTimeString().slice(0, 8);
}

function log(msg) {
  console.log(`[portfolio-watcher] ${ts()} ${msg}`);
}

// ── Filters ───────────────────────────────────────────────────────────────────

function shouldSkip(filename) {
  if (!filename) return true;
  const base = path.basename(filename);
  if (SKIP_NAMES.has(base))         return true;
  if (base.endsWith(SKIP_SUFFIX))   return true;
  if (base.startsWith(SKIP_PREFIX)) return true;
  return false;
}

// ── Health checks ─────────────────────────────────────────────────────────────

async function checkRepl() {
  try {
    const res = await fetch(REPL_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'return 1+1;' }),
      signal: AbortSignal.timeout(4_000),
    });
    if (!res.ok) return false;
    const json = await res.json();
    return json.success === true;
  } catch {
    return false;
  }
}

async function checkPortfolio() {
  try {
    const res = await fetch(PORTFOLIO_URL, {
      method: 'HEAD',
      signal: AbortSignal.timeout(4_000),
    });
    return res.ok || res.status < 500;
  } catch {
    return false;
  }
}

// ── Pipeline runner ───────────────────────────────────────────────────────────

async function runPipeline() {
  if (pipelineRunning) {
    if (!retryQueued) {
      log('busy — will retry after current run completes');
      retryQueued = true;
    }
    return;
  }

  const replReady = await checkRepl();
  if (!replReady) {
    log('WARNING: REPL at :4403 not responding — bridge may be reconnecting. Proceeding anyway.');
  }

  const portfolioUp = await checkPortfolio();
  if (!portfolioUp) {
    log(`WARNING: portfolio dev server at ${PORTFOLIO_URL} is not reachable. Aborting pipeline run.`);
    return;
  }

  log(`Spawning ${PIPELINE.label}...`);
  pipelineRunning = true;

  // build-live-dom-canvas reads the portfolio URL from its own config and
  // doesn't accept --base; screenshot-pipeline expects --base.
  const args = PIPELINE_MODE === 'screenshot'
    ? [PIPELINE_SCRIPT, '--base', PORTFOLIO_URL]
    : [PIPELINE_SCRIPT];

  const child = spawn(process.execPath, args, { stdio: 'inherit' });

  child.on('error', (err) => {
    log(`Pipeline spawn error: ${err.message}`);
  });

  child.on('close', (code) => {
    pipelineRunning = false;
    log(`Pipeline exited with code ${code}.`);

    if (retryQueued) {
      retryQueued = false;
      log('Running queued pipeline trigger...');
      setTimeout(() => runPipeline(), 500);
    }
  });
}

// ── Debounced trigger ─────────────────────────────────────────────────────────

function schedulePipeline(filename) {
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    log(`Change detected (last file: ${filename}). Triggering pipeline...`);
    runPipeline();
  }, DEBOUNCE_MS);
}

// ── File watcher ──────────────────────────────────────────────────────────────

function startWatcher() {
  if (!fs.existsSync(WATCH_DIR)) {
    log(`ERROR: watch directory does not exist: ${WATCH_DIR}`);
    log(`       (set portfolio_dir in ${CONFIG_PATH})`);
    process.exit(1);
  }

  log(`Watching ${WATCH_DIR} (debounce: ${DEBOUNCE_MS}ms)...`);

  const watcher = fs.watch(
    WATCH_DIR,
    { recursive: true },
    (eventType, filename) => {
      if (shouldSkip(filename)) return;
      log(`${eventType}: ${filename}`);
      schedulePipeline(filename);
    }
  );

  watcher.on('error', (err) => {
    log(`Watcher error: ${err.message}`);
  });

  process.on('SIGINT',  () => { log('Received SIGINT. Stopping watcher.');  watcher.close(); process.exit(0); });
  process.on('SIGTERM', () => { log('Received SIGTERM. Stopping watcher.'); watcher.close(); process.exit(0); });
}

// ── Main ──────────────────────────────────────────────────────────────────────

function main() {
  log(`Starting portfolio-watcher (mode: ${PIPELINE_MODE})...`);
  startWatcher();
  log('Watcher active. Waiting for file changes...');
}

main();
