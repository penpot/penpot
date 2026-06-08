#!/usr/bin/env node
/**
 * penpot-bridge.mjs
 *
 * Persistent headless Playwright process that keeps the Penpot MCP plugin
 * connected 24/7 so that the REPL at localhost:4403 is always available.
 *
 * Status API:  GET http://localhost:9002/  → { connected, reloads, lastCheck, uptime }
 *
 * Usage:
 *   node ~/coding-agents/penpot-bridge.mjs
 */

import { chromium } from 'playwright';
import http from 'http';

// ── Config ────────────────────────────────────────────────────────────────────

const PENPOT_URL        = 'http://localhost:9001';
const WORKSPACE_URL     = 'http://localhost:9001/#/workspace/f5ffef08-67a8-8164-8008-1f71a25f3da4/f5ffef08-67a8-8164-8008-1f720087cf79';
const REPL_URL          = 'http://localhost:4403/execute';
const LOGIN_EMAIL       = 'vsaimahit@gmail.com';
const LOGIN_PASSWORD    = 'penpot-local-2026';
const HEARTBEAT_INTERVAL_MS  = 10_000;   // 10s
const POST_NAV_WAIT_MS       = 6_000;    // wait after navigation for plugin iframe
const POST_RELOAD_WAIT_MS    = 8_000;    // wait after reload for reconnect
const STATUS_PORT            = 9002;

// ── State ─────────────────────────────────────────────────────────────────────

const startTime = Date.now();
let connected   = false;
let reloads     = 0;
let lastCheck   = null;
let browser     = null;
let page        = null;

// ── Logging ───────────────────────────────────────────────────────────────────

function ts() {
  return new Date().toTimeString().slice(0, 8); // HH:MM:SS
}

function log(msg) {
  console.log(`[penpot-bridge] ${ts()} ${msg}`);
}

// ── REPL ping ─────────────────────────────────────────────────────────────────

async function pingRepl() {
  try {
    const res = await fetch(REPL_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'return 1+1;' }),
      signal: AbortSignal.timeout(5_000),
    });
    if (!res.ok) return false;
    const json = await res.json();
    return json.success === true;
  } catch {
    return false;
  }
}

// ── Auth / navigation helpers ─────────────────────────────────────────────────

async function isLoggedIn(pg) {
  // Penpot redirects to /login when session is expired
  const url = pg.url();
  return !url.includes('/login') && !url.includes('/auth');
}

async function doLogin(pg) {
  log('Navigating to Penpot login page...');
  await pg.goto(PENPOT_URL, { waitUntil: 'domcontentloaded', timeout: 30_000 });

  // Wait for login form
  await pg.waitForSelector('input[type="email"], input[name="email"], input[placeholder*="mail" i]', { timeout: 15_000 });

  const emailInput = pg.locator('input[type="email"], input[name="email"], input[placeholder*="mail" i]').first();
  const passInput  = pg.locator('input[type="password"]').first();

  await emailInput.fill(LOGIN_EMAIL);
  await passInput.fill(LOGIN_PASSWORD);
  await passInput.press('Enter');

  // Wait until we leave the login page
  await pg.waitForFunction(
    () => !window.location.href.includes('/login') && !window.location.href.includes('/auth'),
    { timeout: 20_000 }
  );
  log('Login successful.');
}

async function navigateToWorkspace(pg) {
  log('Navigating to Portfolio Design workspace...');
  await pg.goto(WORKSPACE_URL, { waitUntil: 'domcontentloaded', timeout: 30_000 });
  log(`Waiting ${POST_NAV_WAIT_MS / 1000}s for plugin iframe to connect...`);
  await sleep(POST_NAV_WAIT_MS);
}

// ── Bootstrap: launch browser and reach workspace ─────────────────────────────

async function bootstrap() {
  log('Launching headless Chromium...');
  browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: 1440, height: 900 },
  });
  page = await context.newPage();

  // Suppress noisy console from Penpot app
  page.on('console', () => {});
  page.on('pageerror', (err) => log(`Page error: ${err.message}`));

  await doLogin(page);
  await navigateToWorkspace(page);
}

// ── Reconnect: reload workspace (or full re-login on failure) ─────────────────

async function reloadWorkspace() {
  reloads++;
  log(`Reloading workspace (reload #${reloads})...`);
  try {
    await page.reload({ waitUntil: 'domcontentloaded', timeout: 30_000 });
    log(`Waiting ${POST_RELOAD_WAIT_MS / 1000}s for plugin reconnect...`);
    await sleep(POST_RELOAD_WAIT_MS);
  } catch (err) {
    log(`Reload failed (${err.message}). Attempting full re-login...`);
    await fullReconnect();
  }
}

async function fullReconnect() {
  log('Performing full re-login sequence...');
  try {
    if (!(await isLoggedIn(page))) {
      await doLogin(page);
    }
    await navigateToWorkspace(page);
  } catch (err) {
    log(`Full reconnect error: ${err.message}. Will retry on next heartbeat.`);
  }
}

// ── Heartbeat loop ────────────────────────────────────────────────────────────

async function heartbeat() {
  lastCheck = new Date().toISOString();
  const ok = await pingRepl();

  if (ok) {
    if (!connected) log('REPL is connected.');
    connected = true;
  } else {
    log('REPL ping failed — attempting workspace reload...');
    connected = false;
    await reloadWorkspace();

    // One more ping after reload
    const okAfter = await pingRepl();
    connected = okAfter;
    if (okAfter) {
      log('REPL reconnected after reload.');
    } else {
      log('REPL still not responding after reload. Will retry next heartbeat.');
    }
  }
}

// ── Status HTTP server ────────────────────────────────────────────────────────

function startStatusServer() {
  const server = http.createServer((req, res) => {
    if (req.method === 'GET' && req.url === '/') {
      const body = JSON.stringify({
        connected,
        reloads,
        lastCheck,
        uptime: Math.floor((Date.now() - startTime) / 1000),
      });
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(body);
    } else {
      res.writeHead(404);
      res.end('Not found');
    }
  });

  server.listen(STATUS_PORT, '127.0.0.1', () => {
    log(`Status server listening on http://localhost:${STATUS_PORT}/`);
  });

  server.on('error', (err) => {
    log(`Status server error: ${err.message}`);
  });
}

// ── Graceful shutdown ─────────────────────────────────────────────────────────

async function shutdown(signal) {
  log(`Received ${signal}. Closing browser...`);
  try {
    if (browser) await browser.close();
  } catch {
    // ignore
  }
  log('Browser closed. Exiting.');
  process.exit(0);
}

process.on('SIGINT',  () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

// ── Utility ───────────────────────────────────────────────────────────────────

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  log('Starting penpot-bridge...');
  startStatusServer();

  try {
    await bootstrap();
  } catch (err) {
    log(`Bootstrap error: ${err.message}`);
    // Don't exit — heartbeat will keep trying to reconnect
  }

  // First heartbeat immediately
  await heartbeat();

  // Recurring heartbeat
  setInterval(async () => {
    try {
      await heartbeat();
    } catch (err) {
      log(`Heartbeat error: ${err.message}`);
      connected = false;
    }
  }, HEARTBEAT_INTERVAL_MS);

  log('Bridge running. Press Ctrl+C to stop.');
}

main().catch((err) => {
  log(`Fatal error: ${err.message}`);
  process.exit(1);
});
