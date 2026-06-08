#!/usr/bin/env node
/**
 * Live Embed health check with auto-retry and screenshot fallback.
 *
 * Checks that:
 *   1. Portfolio (localhost:4321) is reachable
 *   2. MCP REPL (localhost:4403) accepts calls with a plugin connected
 *
 * Retries up to MAX_RETRIES times. If all fail, runs the screenshot pipeline
 * automatically as a fallback so Penpot always has fresh reference frames.
 *
 * Usage:
 *   node ~/coding-agents/live-embed-check.mjs          # check + fallback if broken
 *   node ~/coding-agents/live-embed-check.mjs --check  # exit 0/1 only, no fallback
 */

import { execSync } from 'child_process';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __dir        = dirname(fileURLToPath(import.meta.url));
const PORTFOLIO    = 'http://localhost:4321';
const REPL         = 'http://localhost:4403/execute';
const MAX_RETRIES  = 10;
const RETRY_MS     = 3000;
const CHECK_ONLY   = process.argv.includes('--check');

async function probe() {
  // 1. Portfolio reachable?
  try {
    await fetch(PORTFOLIO, { signal: AbortSignal.timeout(5000) });
  } catch {
    return { ok: false, reason: `Portfolio not reachable at ${PORTFOLIO}` };
  }

  // 2. REPL alive and plugin connected?
  let json;
  try {
    const res = await fetch(REPL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code: 'return typeof penpot !== "undefined" ? "ok" : "no-penpot";' }),
      signal: AbortSignal.timeout(6000),
    });
    json = await res.json();
  } catch (e) {
    return { ok: false, reason: `REPL not reachable: ${e.message}` };
  }

  if (json.error?.includes('No Penpot plugin')) {
    return { ok: false, reason: 'MCP plugin not connected — open the Penpot workspace' };
  }
  if (json.error?.includes('multi-user') || json.error?.includes('userToken')) {
    return { ok: false, reason: 'REPL in multi-user mode — run: docker compose up -d penpot-mcp (single-user)' };
  }
  if (json.error) {
    return { ok: false, reason: `REPL error: ${json.error}` };
  }

  return { ok: true };
}

async function main() {
  console.log('=== Live Embed Health Check ===\n');

  for (let i = 1; i <= MAX_RETRIES; i++) {
    process.stdout.write(`  [${i}/${MAX_RETRIES}] checking... `);
    const r = await probe();

    if (r.ok) {
      console.log('✓ OK');
      console.log('\nLive Embed is healthy — portfolio and MCP plugin both up.\n');
      process.exit(0);
    }

    console.log(`✗  ${r.reason}`);

    if (i < MAX_RETRIES) {
      process.stdout.write(`        retrying in ${RETRY_MS/1000}s...\n`);
      await new Promise(res => setTimeout(res, RETRY_MS));
    }
  }

  console.log(`\n✗ Live Embed failed after ${MAX_RETRIES} attempts.`);

  if (CHECK_ONLY) {
    console.log('  (--check mode: not running fallback)\n');
    process.exit(1);
  }

  console.log('  → Falling back to screenshot pipeline...\n');
  try {
    execSync(`node ${join(__dir, 'screenshot-pipeline.mjs')}`, { stdio: 'inherit' });
  } catch {
    process.exit(1);
  }
}

main().catch(err => { console.error(err.message); process.exit(1); });
