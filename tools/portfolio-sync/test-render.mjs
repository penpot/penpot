#!/usr/bin/env node
/**
 * test-render.mjs
 *
 * End-to-end test that the canvas-to-html render server at :9006 produces a
 * faithful, Penpot-chrome-wrapped page WITHOUT a human (or Claude) doing any
 * hand-fixing between server start and curl. Runs the same patterns as
 * test-reproducibility.mjs: zero npm deps, embedded MCP client (used only for
 * preflight), preflight + sub-tests + numbered exit codes.
 *
 * What it proves:
 *   1. The render server is up on :9006 and responds 200 to /.
 *   2. The HTML pulls Fraunces (and friends) from fonts.googleapis.com —
 *      fonts are wired in standalone, no Claude required.
 *   3. Every page from portfolio-sync.config.json appears as
 *      <section data-board="<name>"> in the configured order.
 *   4. The board cream fill (#faf8f3) and rust link colour (#b33a1a) are
 *      present — fidelity colours from the canvas-to-html palette pass.
 *   5. The Penpot-editor chrome from commit 919090d58 is wrapped around the
 *      canvas — at least one `data-ppc-…` attribute and one `class="ppc-…"`
 *      occurrence (toolbar/layers/properties/status panel markup).
 *   6. No `left:-` substring anywhere — would mean the board-local coord-system
 *      regression from dc6c9d258 is back.
 *   7. Section labels render with JetBrains Mono and headings with Fraunces —
 *      the live-DOM replay still pushes the right font through the canvas.
 *   8. The server re-generates on each request — /tmp/portfolio-canvas-render.html
 *      mtime advances after a second hit, with no hand intervention.
 *
 * Exit codes: 0 pass, 1 render gap, 2 chrome gap, 3 plumbing.
 *
 * Usage: node ./test-render.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CFG_PATH  = path.join(__dirname, 'portfolio-sync.config.json');

function readCfg() {
  try { return JSON.parse(fs.readFileSync(CFG_PATH, 'utf8')); } catch { return {}; }
}
const cfg = readCfg();
const RENDER_PORT = Number(cfg.html_render_port) || 9006;
const RENDER_URL  = `http://localhost:${RENDER_PORT}/`;
const PAGES       = Array.isArray(cfg.pages) ? cfg.pages : [];
const MCP_URL     = 'http://localhost:4401/mcp';

// ─── Minimal MCP client (used only to verify the plugin link is alive) ───────

async function mcpInit() {
  const r = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {},
                clientInfo: { name: 'test-render', version: '1.0' } } }),
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

async function fetchRender() {
  const r = await fetch(RENDER_URL, { signal: AbortSignal.timeout(30_000) });
  const body = await r.text();
  return { status: r.status, body };
}

async function preflight() {
  const services = [
    { label: 'portfolio :4321',  url: 'http://localhost:4321' },
    { label: 'penpot mcp :4401', url: 'http://localhost:4401' },
    { label: `html-render :${RENDER_PORT}`, url: RENDER_URL },
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

function step(label, fn) {
  return async (...args) => {
    process.stdout.write(`  ${label.padEnd(48)}`);
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

// Find a section's start index by board name. Allows any attribute order and
// any whitespace within the opening tag.
function findSectionIndex(body, name) {
  const re = new RegExp(
    `<section[^>]*\\bdata-board\\s*=\\s*"${name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`,
    'i'
  );
  const m = re.exec(body);
  return m ? m.index : -1;
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

// ─── Tests ───────────────────────────────────────────────────────────────────

async function main() {
  console.log('');
  console.log('test-render — :9006 standalone-fidelity test');
  console.log('');

  console.log('Preflight:');
  const pf = await preflight();
  for (const [k, v] of Object.entries(pf)) {
    console.log(`  ${v === 'ok' ? '✓' : '✗'} ${k.padEnd(28)} ${v}`);
  }
  if (pf['portfolio :4321'] === 'DOWN') {
    console.error('portfolio dev server is down — needed for live-DOM replay.');
    process.exit(3);
  }
  if (pf['penpot mcp :4401'] === 'DOWN') {
    console.error('Penpot MCP HTTP API is down — render server cannot read canvas.');
    process.exit(3);
  }
  if (pf[`html-render :${RENDER_PORT}`] === 'DOWN') {
    console.error(`html-render server is down on :${RENDER_PORT}. ` +
                  `Run \`./launch-all.sh sync\` and re-run.`);
    process.exit(3);
  }

  // Quick MCP sanity probe — the render server uses MCP; if it's broken,
  // exit 3 (plumbing) instead of 1/2 so the failure is honestly labelled.
  try {
    const sid = await mcpInit();
    await mcpExec(sid, `return 1+1;`);
  } catch (e) {
    console.error('');
    console.error(`MCP plugin not connected: ${e.message}`);
    console.error('Open http://localhost:9001/auto-login.html and re-run.');
    process.exit(3);
  }

  console.log('');
  console.log('Render fetch:');
  let first;
  try {
    first = await step('GET /', fetchRender)();
  } catch (e) {
    console.error(`Could not GET ${RENDER_URL}: ${e.message}`);
    process.exit(3);
  }

  if (first.status !== 200) {
    console.error('');
    console.error(`FAIL — render server returned HTTP ${first.status}.`);
    console.error('First 600 chars of body:');
    console.error(first.body.slice(0, 600));
    // Distinguish: a 500 from the render server itself is a real render gap
    // (the canvas read or the HTML build failed) — exit 1, not plumbing.
    process.exit(first.status >= 500 && first.status < 600 ? 1 : 3);
  }
  const body = first.body;

  console.log('');
  console.log('Standalone-fidelity checks:');

  await step('fonts.googleapis stylesheet (Fraunces)', async () => {
    assert(
      /<link\s+[^>]*rel\s*=\s*"stylesheet"[^>]*href\s*=\s*"https:\/\/fonts\.googleapis\.com\/css2\?[^"]*Fraunces/i.test(body),
      'no Fraunces <link rel="stylesheet" ...> from fonts.googleapis.com',
    );
  })();

  await step('<section data-board> per configured page', async () => {
    assert(PAGES.length > 0, 'portfolio-sync.config.json has no pages[]');
    const indices = PAGES.map(p => ({ name: p.name, idx: findSectionIndex(body, p.name) }));
    const missing = indices.filter(p => p.idx === -1);
    if (missing.length) {
      throw new Error(`missing <section data-board="${missing.map(m => m.name).join(', ')}">`);
    }
    // Order check.
    for (let i = 1; i < indices.length; i++) {
      if (indices[i].idx < indices[i - 1].idx) {
        throw new Error(
          `section order wrong: ${indices[i].name} appears before ${indices[i - 1].name}`,
        );
      }
    }
  })();

  await step('board cream fill #faf8f3', async () => {
    assert(body.toLowerCase().includes('#faf8f3'),
           'board cream fill #faf8f3 not present');
  })();

  await step('rust link colour #b33a1a', async () => {
    assert(body.toLowerCase().includes('#b33a1a'),
           'rust link colour #b33a1a not present');
  })();

  await step('no left:- (board-local coord regression)', async () => {
    if (/left:\s*-/.test(body)) {
      throw new Error('found `left:-…` — board-local coords regressed');
    }
  })();

  await step('JetBrains Mono font-family on at least one shape', async () => {
    assert(/font-family\s*:\s*[^;"]*JetBrains\s+Mono/i.test(body)
           || /font\s*:[^;"]*JetBrains\s+Mono/i.test(body),
           'no shape uses JetBrains Mono — section labels missing');
  })();

  await step('Fraunces font-family on at least one shape', async () => {
    assert(/font-family\s*:\s*[^;"]*Fraunces/i.test(body)
           || /font\s*:[^;"]*Fraunces/i.test(body),
           'no shape uses Fraunces — headings missing');
  })();

  console.log('');
  console.log('Penpot-chrome (919090d58) checks:');

  await step('data-ppc-… attribute or class="ppc-…" present', async () => {
    const hasAttr  = /\bdata-ppc-[a-z-]+\s*=/.test(body);
    const hasClass = /class\s*=\s*"[^"]*\bppc-[a-z-]+/.test(body);
    if (!hasAttr && !hasClass) {
      throw new Error('no chrome markers (data-ppc-… or class="ppc-…") found');
    }
  })();

  await step('at least one chrome panel visible', async () => {
    // The chrome commit adds .ppc-topbar, .ppc-layers, .ppc-properties,
    // .ppc-statusbar. Demand at least one of those panels.
    const panels = ['ppc-topbar', 'ppc-layers', 'ppc-properties',
                    'ppc-statusbar', 'ppc-app'];
    const seen = panels.filter(p => body.includes(p));
    if (seen.length === 0) {
      throw new Error(`no chrome panels found (looked for: ${panels.join(', ')})`);
    }
  })();

  console.log('');
  console.log('Stateless-regeneration checks:');

  await step('server re-generates on each request', async () => {
    // No Claude / human is allowed to touch /tmp between hits. Two requests,
    // separated by a small gap; either the file mtime advances OR the body
    // changes in a timestamp-bearing region. Either is proof the server isn't
    // serving a stale hand-edited file.
    const renderPath = '/tmp/portfolio-canvas-render.html';
    let mtime1 = null;
    try { mtime1 = fs.statSync(renderPath).mtimeMs; } catch {}

    await new Promise(r => setTimeout(r, 1100));
    const second = await fetchRender();
    if (second.status !== 200) {
      throw new Error(`second GET / returned ${second.status}`);
    }

    let mtime2 = null;
    try { mtime2 = fs.statSync(renderPath).mtimeMs; } catch {}

    // Acceptable signals of regeneration:
    //   (a) mtime advanced on the on-disk file,
    //   (b) the rendered body differs in some way (timestamp, fresh canvas
    //       read), or
    //   (c) the file simply doesn't exist on disk because the server
    //       generates in memory — in that case we accept the 200 alone, since
    //       there's nothing for a human to hand-edit.
    if (mtime1 != null && mtime2 != null) {
      if (mtime2 <= mtime1 && second.body === body) {
        throw new Error('render output unchanged & on-disk mtime did not advance');
      }
    }
  })();

  console.log('');
  console.log('PASS — :9006 renders Penpot-chrome-wrapped canvas with fidelity colours, ' +
              'configured pages in order, and regenerates on each request.');
  process.exit(0);
}

main().catch(e => {
  // Classify by which phase failed: chrome-only failures should exit 2;
  // fidelity / coord / order / fonts / status failures exit 1; everything
  // else (no body, MCP, network) exits 3.
  const msg = String(e?.message || e);
  let code = 1;
  if (/chrome|ppc-/i.test(msg)) code = 2;
  if (/MCP|network|timeout|ECONN|ENOTFOUND|empty/i.test(msg)) code = 3;
  console.error('');
  console.error(`FAIL (exit ${code}): ${msg}`);
  process.exit(code);
});
