#!/usr/bin/env node
/**
 * Portfolio → Penpot Screenshot Pipeline
 *
 * Takes headless screenshots of portfolio pages and imports each one as an
 * image-filled rectangle into whatever Penpot file/page is currently open,
 * via the MCP REPL server at localhost:4403.
 *
 * Prerequisites:
 *   1. Portfolio running  →  cd portfolio && npm run dev  (localhost:4321)
 *   2. Penpot running     →  ~/coding-agents/penpot-launch.sh  (localhost:9001)
 *   3. MCP plugin open in the Penpot workspace (connects to the REPL on :4403)
 *
 * Usage:
 *   node ~/coding-agents/screenshot-pipeline.mjs
 *   node ~/coding-agents/screenshot-pipeline.mjs --full-page   (full-page; viewport-only is default)
 *   node ~/coding-agents/screenshot-pipeline.mjs --base https://myapp.vercel.app
 *   node ~/coding-agents/screenshot-pipeline.mjs --urls '[{"name":"home","path":"/"},{"name":"blog","path":"/blog"}]'
 */

import { chromium } from 'playwright';
import { readFileSync, mkdirSync, writeFileSync } from 'fs';
import { join } from 'path';
import { execSync, execFileSync } from 'child_process';
import http from 'http';

// ── Configuration ────────────────────────────────────────────────────────────

let PORTFOLIO_BASE  = 'http://localhost:4321';
const REPL_URL        = 'http://localhost:4403/execute';
const SCREENSHOT_DIR  = '/tmp/portfolio-screenshots';
const VIEWPORT_WIDTH  = 1440;
const VIEWPORT_HEIGHT = 900;   // initial viewport; full-page captures full scroll height
const FRAME_GAP       = 120;   // horizontal gap between imported frames on canvas
const IMPORT_TIMEOUT  = 90_000; // 90s — large base64 payloads can take a moment

let PAGES = [
  { name: 'home',        path: '/' },
  { name: 'consulting',  path: '/consulting' },
  { name: 'blog',        path: '/blog' },
];

// Fix 1: viewport-only by default; pass --full-page to capture full scroll height
const fullPage = process.argv.includes('--full-page');

// Fix 2: --base and --urls flag parsing
const baseIdx = process.argv.indexOf('--base');
if (baseIdx !== -1 && process.argv[baseIdx + 1]) {
  PORTFOLIO_BASE = process.argv[baseIdx + 1];
}

const urlsIdx = process.argv.indexOf('--urls');
if (urlsIdx !== -1 && process.argv[urlsIdx + 1]) {
  try {
    PAGES = JSON.parse(process.argv[urlsIdx + 1]);
  } catch (e) {
    console.error('Error: --urls value is not valid JSON:', e.message);
    process.exit(1);
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// Only cap if the image is absurdly tall. Earlier this used `sips -Z 1200` which
// downscales the *largest* side and ruined the 1440px width for tall pages.
// `--resampleHeight N` is height-leading: it sets height to N and scales width
// proportionally. So we leave the import alone unless height >12k.
function resizeIfNeeded(filePath, maxHeight = 12000) {
  try {
    const info = execSync(`sips -g pixelHeight "${filePath}" 2>/dev/null`).toString();
    const match = info.match(/pixelHeight:\s*(\d+)/);
    if (!match || parseInt(match[1], 10) <= maxHeight) return;
    execSync(`sips --resampleHeight ${maxHeight} "${filePath}" 2>/dev/null`);
    console.log(`    (capped at ${maxHeight}px height)`);
  } catch { /* sips not available or failed — proceed with full size */ }
}

async function checkService(url, label) {
  try {
    await fetch(url, { signal: AbortSignal.timeout(5000) });
    console.log(`  ✓ ${label}  →  ${url}`);
    return true;
  } catch {
    console.log(`  ✗ ${label}  →  ${url}  (not reachable)`);
    return false;
  }
}

// ── Screenshots ───────────────────────────────────────────────────────────────

async function takeScreenshots() {
  mkdirSync(SCREENSHOT_DIR, { recursive: true });

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: VIEWPORT_WIDTH, height: VIEWPORT_HEIGHT },
  });

  const results = [];

  for (const page of PAGES) {
    const tab = await context.newPage();
    const url  = `${PORTFOLIO_BASE}${page.path}`;

    process.stdout.write(`  ${page.name.padEnd(12)} ${url} ... `);

    try {
      await tab.goto(url, { waitUntil: 'networkidle', timeout: 30_000 });
      // Extra wait for WebGL canvas (consulting page) and font load
      await tab.waitForTimeout(2000);

      const filePath = join(SCREENSHOT_DIR, `${page.name}.png`);
      await tab.screenshot({ path: filePath, fullPage });
      resizeIfNeeded(filePath);

      // Read actual rendered dimensions for log output
      const dims = await tab.evaluate(() => ({
        width:  document.documentElement.scrollWidth,
        height: document.documentElement.scrollHeight,
      }));

      console.log(`done  (${dims.width}×${dims.height})`);
      results.push({ ...page, filePath });
    } catch (err) {
      console.log(`FAILED — ${err.message}`);
    } finally {
      await tab.close();
    }
  }

  await browser.close();
  return results;
}

// ── Temp HTTP server to serve screenshots to the plugin ──────────────────────

const SERVE_PORT = 19321;

function startFileServer() {
  const server = http.createServer((req, res) => {
    const name = req.url.replace(/^\//, '').replace(/[^a-zA-Z0-9._-]/g, '');
    const filePath = join(SCREENSHOT_DIR, name);
    try {
      const data = readFileSync(filePath);
      res.writeHead(200, { 'Content-Type': 'image/png', 'Access-Control-Allow-Origin': '*' });
      res.end(data);
    } catch {
      res.writeHead(404);
      res.end();
    }
  });
  return new Promise(resolve => server.listen(SERVE_PORT, '0.0.0.0', () => resolve(server)));
}

// ── Build the JS code string for one screenshot import ───────────────────────
// The plugin fetches the PNG by URL (no base64 in the REPL request body).

function buildImportCode(url, name, xOffset) {
  return `
    const resp = await fetch('${url}');
    const buf  = await resp.arrayBuffer();
    const u8   = new Uint8Array(buf);
    let b64 = ''; const chunk = 32768;
    for (let i = 0; i < u8.length; i += chunk) {
      b64 += String.fromCharCode.apply(null, u8.subarray(i, i + chunk));
    }
    const base64 = btoa(b64);
    const rect = await penpotUtils.importImage(base64, 'image/png', '${name}', ${xOffset}, 0, ${VIEWPORT_WIDTH}, undefined);
    return { shapeId: rect.id, name: rect.name, x: rect.x, y: rect.y, width: rect.width, height: rect.height };
  `.trim();
}

// ── Penpot import via MCP import_image tool ──────────────────────────────────
// Uses the MCP HTTP API at port 4401. The tool reads files from inside the
// Docker container, so we docker cp each screenshot in first.

const MCP_URL    = 'http://localhost:4401/mcp';
const DOCKER_TMP = '/tmp';  // path inside the penpot-mcp container
const CONTAINER  = 'images-penpot-mcp-1';

function dockerCp(src, dest) {
  execSync(`docker -H unix:///var/run/docker.sock cp "${src}" ${CONTAINER}:${dest}`, { stdio: 'pipe' });
}

async function mcpInit() {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'pipeline', version: '1.0' } } }),
    signal: AbortSignal.timeout(10_000),
  });
  const sid = res.headers.get('mcp-session-id') || res.headers.get('Mcp-Session-Id');
  return sid;
}

async function mcpImport(sid, containerPath, name, x) {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'mcp-session-id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'import_image', arguments: { filePath: containerPath, x, y: 0, width: VIEWPORT_WIDTH } } }),
    signal: AbortSignal.timeout(IMPORT_TIMEOUT),
  });
  const text = await res.text();
  const line = text.split('\n').find(l => l.startsWith('data: '));
  return line ? JSON.parse(line.slice(6)) : null;
}

async function importToPenpot(screenshots) {
  // Init MCP session
  process.stdout.write('  Initializing MCP session... ');
  let sid;
  try { sid = await mcpInit(); } catch (e) { console.log(`FAILED — ${e.message}`); printManualInstructions(screenshots); return; }
  if (!sid) { console.log('no session'); printManualInstructions(screenshots); return; }
  console.log('ok ✓');

  let xOffset = 0;
  for (const shot of screenshots) {
    const fileName = shot.filePath.split('/').pop();
    const containerPath = `${DOCKER_TMP}/${fileName}`;
    const name = `portfolio-${shot.name}`;
    process.stdout.write(`  ${name.padEnd(24)} x=${xOffset} ... `);
    try {
      // Copy file into container
      dockerCp(shot.filePath, containerPath);
      // Import via MCP
      const json = await mcpImport(sid, containerPath, name, xOffset);
      const shapeId = json?.result?.content?.[0]?.text;
      if (shapeId && !shapeId.includes('failed')) {
        console.log(`done  →  ${shapeId.slice(0, 60)}`);
        xOffset += VIEWPORT_WIDTH + FRAME_GAP;
      } else {
        console.log(`FAILED — ${shapeId || 'no response'}`);
      }
    } catch (err) {
      console.log(`FAILED — ${err.message}`);
    }
  }
}

// ── Manual fallback when REPL is in multi-user mode ──────────────────────────

function printManualInstructions(screenshots) {
  console.log(`
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
  REPL is in multi-user mode — auto-import blocked.
  Screenshots were saved successfully.

  OPTION 1 — Fix (one time):
    Edit  ~/coding-agents/repos/penpot/docker/images/docker-compose.yaml
    Add   command: ["node", "index.js"]   under penpot-mcp service
    Then  cd ~/coding-agents/repos/penpot/docker/images && docker compose up -d penpot-mcp
    Re-run this script.

  OPTION 2 — Manual drag-and-drop:
    Open Penpot → your file → drag these PNGs onto the canvas:
`);
  for (const s of screenshots) {
    console.log(`      ${s.filePath}`);
  }
  console.log(`
  OPTION 3 — Paste code into the REPL at http://localhost:4403
    (open that URL in the browser where Penpot is running, then paste):
`);
  let x = 0;
  for (const s of screenshots) {
    const base64 = readFileSync(s.filePath).toString('base64');
    const name   = `portfolio-${s.name}`;
    const code   = buildImportCode(base64, name, x);
    console.log(`  // --- ${name} ---`);
    console.log(`  (async () => { ${code} })()\n`);
    x += VIEWPORT_WIDTH + FRAME_GAP;
  }
  console.log(`━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━`);
}

// ── HTML report ───────────────────────────────────────────────────────────────

function generateHTMLReport(screenshots, outPath) {
  const now = new Date().toLocaleString();
  const items = screenshots.map(s => {
    const b64 = readFileSync(s.filePath).toString('base64');
    return `
      <section>
        <h2>${s.name}</h2>
        <a href="${s.filePath}" target="_blank">
          <img src="data:image/png;base64,${b64}" alt="${s.name}" />
        </a>
        <p class="meta">${s.filePath}</p>
      </section>`;
  }).join('\n');

  const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Portfolio Screenshots — ${now}</title>
<style>
  body { font-family: system-ui, sans-serif; background: #111; color: #eee; margin: 0; padding: 24px; }
  h1   { font-size: 18px; margin-bottom: 20px; color: #aaa; }
  section { margin-bottom: 40px; }
  h2   { font-size: 14px; font-weight: 600; margin-bottom: 8px; color: #ccc; letter-spacing: .04em; text-transform: uppercase; }
  img  { max-width: 100%; border: 1px solid #333; border-radius: 4px; display: block; }
  .meta { font-size: 11px; color: #555; margin-top: 6px; font-family: monospace; }
</style>
</head>
<body>
<h1>Portfolio Screenshots · ${now}</h1>
${items}
</body>
</html>`;
  writeFileSync(outPath, html);
}

// ── Main ──────────────────────────────────────────────────────────────────────

async function main() {
  const mode = fullPage ? 'full-page' : 'viewport-only';
  console.log(`\n=== Portfolio → Penpot Screenshot Pipeline  [${mode}] ===\n`);

  // Preflight
  console.log('Checking services...');
  const [portfolioOk, mcpOk] = await Promise.all([
    checkService(PORTFOLIO_BASE,       'Portfolio'),
    checkService('http://localhost:4401', 'Penpot MCP'),
  ]);

  if (!portfolioOk) {
    console.error('\n  Fix: cd /Users/svaddadi/Documents/GitHub/websites/portfolio && npm run dev');
    process.exit(1);
  }
  if (!mcpOk) {
    console.error('\n  Fix: start Penpot → ~/coding-agents/launch-all.sh penpot');
    process.exit(1);
  }

  // Screenshots
  console.log('\nTaking screenshots...');
  const screenshots = await takeScreenshots();

  if (screenshots.length === 0) {
    console.error('\nNo screenshots succeeded. Aborting.');
    process.exit(1);
  }

  // HTML report (always generated, fast, no dependencies)
  const reportPath = join(SCREENSHOT_DIR, 'index.html');
  generateHTMLReport(screenshots, reportPath);
  console.log(`\nHTML report: file://${reportPath}`);

  // Import into Penpot
  console.log(`\nImporting into Penpot (${screenshots.length} frame${screenshots.length > 1 ? 's' : ''})...`);
  await importToPenpot(screenshots);

  console.log('\n✓ Done. Frames are on your Penpot canvas, laid out left-to-right.');
  console.log(`  PNGs + HTML report saved in: ${SCREENSHOT_DIR}\n`);
}

main().catch(err => {
  console.error('\nPipeline error:', err.message);
  process.exit(1);
});
