#!/usr/bin/env node
/**
 * test-reproducibility.mjs
 *
 * End-to-end reproducibility test for the portfolio↔Penpot live-render pipe.
 * Run with no setup — the test brings the canvas to baseline itself.
 *
 * Pass criteria:
 *   1. After `build-live-dom-canvas.mjs` runs on a clean board, every page's
 *      `diagnose-live-render` verdict is LIVE-ISH (not SCREENSHOT/EMPTY/PARTIAL).
 *   2. Running the build a SECOND time produces a board with the SAME number
 *      of children per page (idempotency — no duplicate shapes accumulate).
 *   3. No MCP "Tool execution failed" errors anywhere in build output.
 *
 * Exit codes: 0 pass, 1 first-run failure, 2 idempotency failure, 3 plumbing.
 *
 * Usage: node ./test-reproducibility.mjs
 *        node ./test-reproducibility.mjs --quick   (skip the idempotency run)
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const BUILD     = path.join(__dirname, 'build-live-dom-canvas.mjs');
const DIAGNOSE  = path.join(__dirname, 'diagnose-live-render.mjs');

const QUICK = process.argv.includes('--quick');

const MCP_URL = 'http://localhost:4401/mcp';

// ─── Minimal MCP client (duplicated so the test has zero source deps) ────────

async function mcpInit() {
  const r = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {},
                clientInfo: { name: 'test-repro', version: '1.0' } } }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!r.ok) throw new Error(`MCP init ${r.status}`);
  const sid = r.headers.get('mcp-session-id') || r.headers.get('Mcp-Session-Id');
  await r.text();
  if (!sid) throw new Error('no mcp session id');
  return sid;
}

async function mcpExec(sid, code) {
  const r = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'mcp-session-id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'execute_code', arguments: { code } } }),
    signal: AbortSignal.timeout(30_000),
  });
  const text = await r.text();
  const line = text.split('\n').find(l => l.startsWith('data: '));
  if (!line) throw new Error('mcp empty');
  const parsed = JSON.parse(line.slice(6));
  const payload = parsed?.result?.content?.[0]?.text;
  if (!payload) throw new Error('mcp empty payload');
  if (/^Tool execution failed/.test(payload)) throw new Error(payload);
  return JSON.parse(payload);
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

function run(cmd, args) {
  return new Promise((resolve, reject) => {
    let stdout = '', stderr = '';
    const child = spawn(cmd, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', d => { stdout += d.toString(); });
    child.stderr.on('data', d => { stderr += d.toString(); });
    child.on('error', reject);
    child.on('close', code => resolve({ code, stdout, stderr }));
  });
}

async function countBoardChildren(sid) {
  // Count children under each board on Page 1 (or the first page if none is literally named "Page 1").
  const r = await mcpExec(sid, `
const cur = penpot.currentFile.pages.find(p => p.name === 'Page 1') || penpot.currentFile.pages[0];
if (!cur) return { error: 'no pages in file' };
const shapes = cur.findShapes();
const boards = shapes.filter(s => s.type === 'board' && s.id !== '00000000-0000-0000-0000-000000000000');
const out = {};
for (const b of boards) {
  const kids = shapes.filter(c => c.parent && c.parent.id === b.id);
  out[b.name] = kids.length;
}
return out;
`);
  return r.result;
}

async function preflight() {
  const services = [
    { label: 'portfolio',   url: 'http://localhost:4321' },
    { label: 'penpot mcp',  url: 'http://localhost:4401' },
    { label: 'iframe plugin', url: 'http://localhost:9005' },
  ];
  const status = {};
  for (const s of services) {
    try {
      await fetch(s.url, { signal: AbortSignal.timeout(3_000) });
      status[s.label] = 'ok';
    } catch {
      status[s.label] = 'DOWN';
    }
  }
  return status;
}

function parseDiagnoseVerdicts(stdout) {
  // Find "VERDICT XXXX" lines per page.
  const verdicts = {};
  const re = /── (\w+)\s+\(.*?\) ──[\s\S]*?VERDICT\s+(\S+)/g;
  let m;
  while ((m = re.exec(stdout)) !== null) {
    verdicts[m[1]] = m[2];
  }
  return verdicts;
}

function step(label, fn) {
  return async (...args) => {
    process.stdout.write(`  ${label.padEnd(36)}`);
    const t0 = Date.now();
    try {
      const out = await fn(...args);
      console.log(`✓ (${Date.now() - t0}ms)`);
      return out;
    } catch (e) {
      console.log(`✗ ${e.message}`);
      throw e;
    }
  };
}

// ─── Tests ───────────────────────────────────────────────────────────────────

async function main() {
  console.log('');
  console.log('test-reproducibility — portfolio↔Penpot live-render pipe');
  console.log('');

  console.log('Preflight:');
  const pf = await preflight();
  for (const [k, v] of Object.entries(pf)) {
    console.log(`  ${v === 'ok' ? '✓' : '✗'} ${k.padEnd(20)} ${v}`);
  }
  if (pf['portfolio'] === 'DOWN' || pf['penpot mcp'] === 'DOWN') {
    console.error('Required services are down. Cannot run.');
    process.exit(3);
  }

  let sid;
  try {
    sid = await mcpInit();
    await mcpExec(sid, `return 1+1;`);
  } catch (e) {
    console.error('');
    console.error(`MCP plugin is not connected: ${e.message}`);
    console.error('Open http://localhost:9001/auto-login.html in a browser, wait for the workspace + MCP panel to load, then re-run.');
    process.exit(3);
  }
  console.log('');

  console.log('Run 1 — clean build:');
  const run1 = await step('build-live-dom-canvas',
    () => run(process.execPath, [BUILD]))();
  if (run1.code !== 0) {
    console.error('Build exited non-zero:', run1.code);
    console.error(run1.stdout.slice(-500));
    console.error(run1.stderr.slice(-500));
    process.exit(1);
  }
  if (/Tool execution failed/.test(run1.stdout)) {
    console.error('Build output contained "Tool execution failed":');
    console.error(run1.stdout.match(/Tool execution failed[^\n]*/)?.[0]);
    process.exit(1);
  }
  const counts1 = await step('count board children', () => countBoardChildren(sid))();
  console.log('    counts:', JSON.stringify(counts1));

  const diag1 = await step('diagnose-live-render',
    () => run(process.execPath, [DIAGNOSE]))();
  if (diag1.code !== 0) {
    console.error('Diagnose exited non-zero.');
    process.exit(1);
  }
  const verdicts1 = parseDiagnoseVerdicts(diag1.stdout);
  console.log('    verdicts:', JSON.stringify(verdicts1));

  const failedPages = Object.entries(verdicts1).filter(([, v]) => v !== 'LIVE-ISH');
  if (failedPages.length > 0) {
    console.error('');
    console.error('FAIL — not all pages are LIVE-ISH after first build:');
    for (const [p, v] of failedPages) console.error(`  ${p}: ${v}`);
    console.error('');
    console.error('Last 30 lines of build output:');
    console.error(run1.stdout.split('\n').slice(-30).join('\n'));
    process.exit(1);
  }

  if (QUICK) {
    console.log('');
    console.log('PASS — all pages LIVE-ISH. (--quick: skipping idempotency run)');
    process.exit(0);
  }

  console.log('');
  console.log('Run 2 — idempotency:');
  const run2 = await step('build-live-dom-canvas (again)',
    () => run(process.execPath, [BUILD]))();
  if (run2.code !== 0) {
    console.error('Second build exited non-zero.');
    process.exit(2);
  }
  if (/Tool execution failed/.test(run2.stdout)) {
    console.error('Second build had a Tool execution failed.');
    process.exit(2);
  }
  const counts2 = await step('count board children', () => countBoardChildren(sid))();
  console.log('    counts:', JSON.stringify(counts2));

  const drift = {};
  for (const k of new Set([...Object.keys(counts1), ...Object.keys(counts2)])) {
    if (counts1[k] !== counts2[k]) drift[k] = `${counts1[k]} → ${counts2[k]}`;
  }
  if (Object.keys(drift).length) {
    console.error('');
    console.error('FAIL — board child counts changed between runs (shapes are accumulating):');
    console.error(JSON.stringify(drift, null, 2));
    process.exit(2);
  }

  console.log('');
  console.log('PASS — both runs produced identical board child counts. LIVE-ISH everywhere.');
  process.exit(0);
}

main().catch(e => {
  console.error('Test error:', e.message);
  process.exit(3);
});
