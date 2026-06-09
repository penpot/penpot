#!/usr/bin/env node
/**
 * canvas-to-html.mjs
 *
 * Reads the current state of every board on Penpot's "Page 1" (via the MCP
 * REPL plugin), then emits a static HTML document that mirrors each board as
 * an absolutely-positioned <section> with one HTML element per shape.
 *
 * Two modes:
 *   1. one-shot generator  → writes /tmp/portfolio-canvas-render.html and exits
 *   2. server (--serve)    → serves the generated HTML on http://127.0.0.1:<port>
 *                            and re-generates on every request, so a browser
 *                            refresh = the latest canvas state.
 *
 * Conventions match the other portfolio-sync background services
 * (penpot-bridge, portfolio-watcher, webhook-server, live-preview-server):
 *   PID  → /tmp/canvas-to-html.pid
 *   log  → /tmp/canvas-to-html.log
 *
 * Zero npm deps — built-in `http`, `fs`, `path`, `url` only.
 *
 * Flags:
 *   --out <path>      write the static HTML to a different path
 *   --board <name>    only render that one board
 *   --inline-css      embed CSS in a <style> tag (default behavior)
 *   --serve           also start an HTTP server on the configured port
 */

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname  = path.dirname(__filename);
const CONFIG_PATH = path.join(__dirname, 'portfolio-sync.config.json');

function readConfig() {
  try { return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8')); } catch { return {}; }
}

const cfg = readConfig();
const MCP_URL    = 'http://localhost:4401/mcp';
const HOST       = '127.0.0.1';
const PORT       = Number(cfg.html_render_port) || 9006;
const DEFAULT_OUT = '/tmp/portfolio-canvas-render.html';

const args        = process.argv.slice(2);
const OUT_IDX     = args.indexOf('--out');
const OUT_PATH    = OUT_IDX !== -1 ? args[OUT_IDX + 1] : DEFAULT_OUT;
const BOARD_IDX   = args.indexOf('--board');
const BOARD_PICK  = BOARD_IDX !== -1 ? args[BOARD_IDX + 1] : null;
const SERVE_MODE  = args.includes('--serve');
// `--inline-css` is the default; the flag is accepted for future-proofing.
const INLINE_CSS  = true;

// ─── MCP plumbing (same pattern as build-live-dom-canvas.mjs) ─────────────────

async function mcpInit() {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {},
                clientInfo: { name: 'canvas-to-html', version: '1.0' } },
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) throw new Error(`MCP init failed: ${res.status}`);
  const sid = res.headers.get('mcp-session-id') || res.headers.get('Mcp-Session-Id');
  await res.text();
  if (!sid) throw new Error('no MCP session id');
  return sid;
}

async function mcpExec(sid, code) {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream', 'mcp-session-id': sid },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 2, method: 'tools/call',
      params: { name: 'execute_code', arguments: { code } },
    }),
    signal: AbortSignal.timeout(60_000),
  });
  const text = await res.text();
  const line = text.split('\n').find(l => l.startsWith('data: '));
  if (!line) throw new Error('MCP empty data frame');
  const parsed = JSON.parse(line.slice(6));
  const payload = parsed?.result?.content?.[0]?.text;
  if (!payload) {
    if (parsed?.result?.isError) throw new Error('MCP tool error: ' + JSON.stringify(parsed.result));
    throw new Error('MCP empty content: ' + JSON.stringify(parsed));
  }
  if (/^Tool execution failed/.test(payload)) throw new Error(payload);
  try { return JSON.parse(payload); } catch { return { raw: payload }; }
}

// ─── Canvas read ──────────────────────────────────────────────────────────────

/**
 * Reads every page-1 board and its children. Shapes carry `name` prefixes
 * written by build-live-dom-canvas (e.g. "link: /consulting", "img: alt",
 * "control: input"); we use those prefixes to recover the original `kind`.
 *
 * We deliberately limit ourselves to data that crosses MCP cheaply — color,
 * font, geometry, text content, link href (parsed from the shape name).
 */
async function readCanvas(sid) {
  const code = `
const cur = penpot.currentFile.pages.find(p => p.name === 'Page 1') || penpot.currentFile.pages[0];
if (!cur) return { error: 'no pages' };
if (penpot.currentPage.id !== cur.id) penpot.openPage(cur);

const ROOT_ID = '00000000-0000-0000-0000-000000000000';
const all = cur.findShapes();
const boards = all.filter(s => s.type === 'board' && s.id !== ROOT_ID
                                && s.parent && s.parent.id === ROOT_ID);

const out = [];
for (const b of boards) {
  const fill = (b.fills && b.fills[0]) || null;
  const children = all.filter(s => s.parent && s.parent.id === b.id);
  const shapes = children.map(s => {
    const f = (s.fills && s.fills[0]) || null;
    const stroke = (s.strokes && s.strokes[0]) || null;
    // text shapes — text content via .characters; font props on the shape.
    let chars = null;
    try { chars = (typeof s.characters === 'string') ? s.characters : null; } catch (_) {}
    return {
      id: s.id, type: s.type, name: s.name || '',
      x: s.x | 0, y: s.y | 0, w: s.width | 0, h: s.height | 0,
      opacity: typeof s.opacity === 'number' ? s.opacity : 1,
      borderRadius: typeof s.borderRadius === 'number' ? s.borderRadius : 0,
      fillColor: f ? f.fillColor || null : null,
      fillOpacity: f && typeof f.fillOpacity === 'number' ? f.fillOpacity : null,
      hasFillImage: f ? !!(f.fillImage || f.fillImageUrl || f.fillImageData) : false,
      strokeColor: stroke ? stroke.strokeColor || null : null,
      strokeStyle: stroke ? stroke.strokeStyle || null : null,
      strokeWidth: stroke && typeof stroke.strokeWidth === 'number' ? stroke.strokeWidth : null,
      text: chars,
      fontFamily: typeof s.fontFamily === 'string' ? s.fontFamily : null,
      fontSize:   typeof s.fontSize === 'string' ? s.fontSize : null,
      fontWeight: typeof s.fontWeight === 'string' ? s.fontWeight : null,
      textDecoration: typeof s.textDecoration === 'string' ? s.textDecoration : null,
    };
  });
  out.push({
    id: b.id, name: b.name || 'board',
    x: b.x | 0, y: b.y | 0, w: b.width | 0, h: b.height | 0,
    fillColor: fill ? fill.fillColor || null : null,
    shapes,
  });
}
return { boards: out };
`;
  return mcpExec(sid, code);
}

// ─── Shape → HTML mapping ─────────────────────────────────────────────────────

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function escapeAttr(s) { return escapeHtml(s); }

/**
 * Derive shape kind. build-live-dom-canvas tags each shape via its `name`
 * field with the kind as a prefix; fall back on shape type/heuristics for
 * shapes that pre-date this convention or that the user added by hand.
 */
function classifyShape(s) {
  const name = (s.name || '').toLowerCase();
  if (name.startsWith('link:'))    return { kind: 'link',    href: (s.name.slice(5) || '').trim() };
  if (name.startsWith('control:')) return { kind: 'control', tag: (s.name.slice(8) || '').trim() };
  if (name.startsWith('img:'))     return { kind: 'image',   alt: (s.name.slice(4) || '').trim() };
  if (name.startsWith('svg:'))     return { kind: 'svg' };
  if (name === 'line')             return { kind: 'line' };
  if (name === 'screenshot-backdrop') return { kind: 'backdrop' };
  if (name === 'heading' || /^h[1-6]$/.test(name)) return { kind: 'heading' };
  if (name === 'paragraph')        return { kind: 'paragraph' };
  if (name === 'button')           return { kind: 'button' };
  // Shape-type fallbacks: text shapes default to paragraph, rectangles default
  // to line (rare for hand-added shapes — most generated content carries one
  // of the prefixes above).
  if (s.type === 'text')           return { kind: 'paragraph' };
  if (s.type === 'rectangle' && s.hasFillImage) return { kind: 'image', alt: '' };
  if (s.type === 'rectangle')      return { kind: 'line' };
  return { kind: 'paragraph' };
}

function shapeStyle(s, extra = '') {
  const parts = [
    'position:absolute',
    `left:${s.x}px`,
    `top:${s.y}px`,
    `width:${s.w}px`,
  ];
  // Don't pin `height` for text — let the content reflow vertically — but DO
  // pin it for placeholders/lines/controls so the layout stays geometrically
  // truthful.
  if (extra && extra.includes('height:')) {
    parts.push(extra);
  } else {
    parts.push(extra);
  }
  return parts.filter(Boolean).join(';');
}

function fontShorthand(s) {
  const fam = s.fontFamily ? `"${s.fontFamily.replace(/"/g, '')}"` : 'system-ui';
  const size = s.fontSize ? `${parseInt(s.fontSize, 10) || 16}px` : '16px';
  const weight = s.fontWeight || '400';
  return `${weight} ${size}/1.35 ${fam}, system-ui, sans-serif`;
}

function renderShape(s) {
  const k = classifyShape(s);
  const color = s.fillColor || '#0a0a0a';
  // Reason: child x/y are already board-local (Penpot reports board children
  // in board-local space), and the enclosing <section> is `position:relative`
  // so left/top resolve against the section origin directly.
  const baseStyle = `position:absolute;left:${s.x}px;top:${s.y}px;width:${s.w}px;`;

  if (k.kind === 'backdrop') {
    // Screenshot backdrop is faded on the canvas — replicate as a faint label.
    return ''; // skip entirely; the section background carries enough context
  }

  if (k.kind === 'line') {
    const fillColor = s.fillColor || '#cccccc';
    const opacity = s.fillOpacity != null ? `;opacity:${s.fillOpacity}` : '';
    return `<div style="${baseStyle}height:${Math.max(1, s.h)}px;background-color:${escapeAttr(fillColor)}${opacity}"></div>`;
  }

  if (k.kind === 'image' || k.kind === 'svg') {
    const label = (k.alt || s.name || k.kind).slice(0, 80);
    const bg = s.fillColor || '#f5f5f5';
    const border = s.strokeColor || '#888888';
    return `<div style="${baseStyle}height:${s.h}px;background:${escapeAttr(bg)};border:1px dashed ${escapeAttr(border)};box-sizing:border-box;display:flex;align-items:center;justify-content:center;color:#555;font:12px system-ui,sans-serif;text-align:center;padding:4px">${escapeHtml(label)}</div>`;
  }

  if (k.kind === 'control') {
    const placeholder = (s.text || k.tag || 'input').slice(0, 120);
    const isTextarea = /textarea/i.test(k.tag);
    const style = `${baseStyle}height:${s.h}px;box-sizing:border-box;border:1px solid #cccccc;border-radius:4px;padding:4px 8px;font:14px system-ui,sans-serif`;
    if (isTextarea) {
      return `<textarea style="${style}" placeholder="${escapeAttr(placeholder)}"></textarea>`;
    }
    return `<input style="${style}" placeholder="${escapeAttr(placeholder)}" />`;
  }

  // Text-like kinds: heading / paragraph / link / button
  const font = fontShorthand(s);
  const text = escapeHtml(s.text || '');
  const decoration = s.textDecoration === 'underline' ? ';text-decoration:underline' : '';
  const textStyle = `${baseStyle}font:${font};color:${escapeAttr(color)};margin:0${decoration}`;

  if (k.kind === 'link') {
    const href = k.href || '#';
    return `<a href="${escapeAttr(href)}" style="${textStyle}">${text}</a>`;
  }
  if (k.kind === 'button') {
    return `<button style="${textStyle};background:transparent;border:0;cursor:pointer;text-align:left">${text}</button>`;
  }
  if (k.kind === 'heading') {
    return `<h2 style="${textStyle}">${text}</h2>`;
  }
  return `<p style="${textStyle}">${text}</p>`;
}

// ─── Document assembly ───────────────────────────────────────────────────────

function buildHtml(boards) {
  // Reason: boards are laid out side-by-side on the canvas with an x-offset.
  // Mirror that horizontal flow in the static page so the user sees the same
  // arrangement they see in Penpot. Each <section> is `position:relative` and
  // shape coords are already board-local, so they resolve directly against
  // the section origin.
  const sections = boards.map(b => {
    const bgColor = b.fillColor || '#ffffff';
    const shapes  = b.shapes.map(s => renderShape(s)).filter(Boolean).join('\n      ');
    return `    <section data-board="${escapeAttr(b.name)}" style="position:relative;width:${b.w}px;height:${b.h}px;background-color:${escapeAttr(bgColor)};flex:0 0 auto">
      <div style="position:absolute;top:8px;left:8px;font:11px/1 ui-monospace,Menlo,monospace;color:rgba(0,0,0,0.35);z-index:9999;pointer-events:none">${escapeHtml(b.name)} · ${b.w}×${b.h}</div>
      ${shapes}
    </section>`;
  }).join('\n');

  const css = INLINE_CSS ? `
    body { margin: 0; padding: 0; background: #ececec; font-family: system-ui, sans-serif; }
    .canvas-wrap { display: flex; gap: 32px; padding: 32px; align-items: flex-start; min-width: max-content; }
    section[data-board] { box-shadow: 0 4px 24px rgba(0,0,0,0.08); overflow: hidden; }
  ` : '';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>Penpot canvas render</title>
${INLINE_CSS ? `<style>${css}</style>` : ''}
</head>
<body>
  <div class="canvas-wrap">
${sections}
  </div>
</body>
</html>
`;
}

function buildEmptyHtml(message) {
  // 503-ish HTML body used when MCP is unreachable — keeps the server quiet
  // instead of crashing, with a clear pointer to the auto-login page.
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<title>Penpot canvas render — waiting</title>
<style>
  body { margin: 0; padding: 48px; background: #fafafa; font: 15px/1.5 system-ui, sans-serif; color: #222; max-width: 720px; }
  code { background: #eee; padding: 2px 6px; border-radius: 4px; }
  a { color: #1057d6; }
</style>
</head>
<body>
  <h1>Canvas render not available</h1>
  <p>${escapeHtml(message)}</p>
  <p>If the Penpot plugin isn't connected, open
    <a href="http://localhost:9001/auto-login.html">http://localhost:9001/auto-login.html</a>
    to launch the editor and re-attach the MCP REPL plugin, then refresh this page.</p>
</body>
</html>
`;
}

// ─── Generation entry-point ──────────────────────────────────────────────────

async function generate({ boardFilter = null } = {}) {
  const sid = await mcpInit();
  const res = await readCanvas(sid);
  if (res?.result?.error) throw new Error(res.result.error);
  let boards = res?.result?.boards || [];
  if (boardFilter) boards = boards.filter(b => b.name === boardFilter);
  return buildHtml(boards);
}

// ─── CLI / Server ────────────────────────────────────────────────────────────

async function runOnce() {
  try {
    const html = await generate({ boardFilter: BOARD_PICK });
    fs.writeFileSync(OUT_PATH, html);
    console.log(`[canvas-to-html] wrote ${OUT_PATH} (${html.length} bytes)`);
    return 0;
  } catch (err) {
    console.error(`[canvas-to-html] error: ${err.message}`);
    return 1;
  }
}

function isMcpDownError(msg) {
  return /No Penpot plugin instances are currently connected/i.test(msg)
      || /MCP init failed/i.test(msg)
      || /ECONNREFUSED/i.test(msg)
      || /fetch failed/i.test(msg);
}

function startServer() {
  const server = http.createServer(async (req, res) => {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', '*');

    if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      res.writeHead(405, { 'Content-Type': 'text/plain' });
      res.end('Method Not Allowed');
      return;
    }

    // We ignore the URL path — there's a single endpoint that re-renders the
    // whole canvas. Sub-resources (favicon etc.) get the same body so we don't
    // 404 the browser into the console.
    try {
      const url = new URL(req.url, `http://${HOST}:${PORT}`);
      const boardQ = url.searchParams.get('board') || BOARD_PICK || null;
      const html = await generate({ boardFilter: boardQ });
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Length': Buffer.byteLength(html),
        'Cache-Control': 'no-store',
      });
      if (req.method === 'HEAD') { res.end(); return; }
      res.end(html);
    } catch (err) {
      const msg = err && err.message || String(err);
      console.error(`[canvas-to-html] request error: ${msg}`);
      if (isMcpDownError(msg)) {
        const body = buildEmptyHtml(`The Penpot MCP plugin is not currently connected (${msg}).`);
        res.writeHead(503, {
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Length': Buffer.byteLength(body),
          'Cache-Control': 'no-store',
        });
        res.end(body);
      } else {
        const body = buildEmptyHtml(`Render failed: ${msg}`);
        res.writeHead(500, {
          'Content-Type': 'text/html; charset=utf-8',
          'Content-Length': Buffer.byteLength(body),
          'Cache-Control': 'no-store',
        });
        res.end(body);
      }
    }
  });

  server.on('error', (err) => {
    console.error(`[canvas-to-html] server error: ${err.message}`);
  });

  server.listen(PORT, HOST, () => {
    console.log(`[canvas-to-html] listening on http://${HOST}:${PORT}`);
    console.log(`[canvas-to-html] each request re-reads the Penpot canvas (MCP @ ${MCP_URL})`);
  });

  process.on('uncaughtException',  (err) => console.error('[canvas-to-html] uncaughtException:', err && err.stack || err));
  process.on('unhandledRejection', (err) => console.error('[canvas-to-html] unhandledRejection:', err));
}

async function main() {
  if (SERVE_MODE) {
    startServer();
    return;
  }
  const code = await runOnce();
  process.exit(code);
}

main().catch(err => { console.error('[canvas-to-html] fatal:', err.message); process.exit(1); });
