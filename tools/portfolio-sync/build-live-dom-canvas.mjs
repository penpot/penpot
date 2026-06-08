#!/usr/bin/env node
/**
 * build-live-dom-canvas.mjs
 *
 * Replace each board's flat screenshot with editable Penpot shapes derived from
 * the live DOM of the portfolio. After running this, diagnose-live-render.mjs
 * should report each board as LIVE-ISH rather than SCREENSHOT.
 *
 * Pipeline per page:
 *
 *   1. Playwright loads {portfolio_local}/<path> at 1440 width and walks
 *      visible semantic elements: headings, paragraphs, list items (with a
 *      bullet glyph prefixed for <ul> / <ol>), pre/code blocks, anchors,
 *      buttons, controls, images, <hr> and CSS divider/border lines, and
 *      <svg> placeholders. Pages are harvested in parallel.
 *
 *   2. The matching Penpot board is grown to the document height, the existing
 *      screenshot child is reparented + resized to the new board frame and
 *      faded so the layout still hints behind the text, and every other prior
 *      generated child is wiped (shapes whose name starts with the preserve
 *      prefix survive). Idempotent across reruns.
 *
 *   3. Each captured element becomes a penpot.createText (or createRectangle
 *      for lines, svg placeholders, controls, images). Hyperlinks get a link
 *      colour, an underline, and the href stored in the shape name
 *      ("link: /consulting"). If a paragraph is dominated by a single inline
 *      link the paragraph is dropped — the link reads on its own, no overlap.
 *
 * Flags:
 *   --page <name>                only build one of {home,consulting,blog}
 *   --reset-board                drop the screenshot backdrop too (clean slate)
 *   --batch <n>                  override batch size (default 24)
 *   --preserve-prefix <str>      shapes whose name starts with this prefix
 *                                survive the wipe (default "preserve-")
 */

import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname   = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, 'portfolio-sync.config.json');

function readConfig() {
  try { return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8')); } catch { return {}; }
}

const cfg            = readConfig();
const PORTFOLIO_URL  = cfg.portfolio_local || 'http://localhost:4321';
const MCP_URL        = 'http://localhost:4401/mcp';
const ALL_PAGES      = cfg.pages || [
  { name: 'home', path: '/' },
  { name: 'consulting', path: '/consulting' },
  { name: 'blog', path: '/blog' },
];

const args        = process.argv.slice(2);
const RESET_BOARD = args.includes('--reset-board');
const PAGE_IDX    = args.indexOf('--page');
const PAGE_PICK   = PAGE_IDX !== -1 ? args[PAGE_IDX + 1] : null;
const PAGES       = PAGE_PICK ? ALL_PAGES.filter(p => p.name === PAGE_PICK) : ALL_PAGES;
const BATCH_IDX   = args.indexOf('--batch');
const BATCH_SIZE  = BATCH_IDX !== -1 ? Math.max(1, parseInt(args[BATCH_IDX + 1], 10)) : 24;
const PRESERVE_IDX     = args.indexOf('--preserve-prefix');
const PRESERVE_PREFIX  = PRESERVE_IDX !== -1 ? args[PRESERVE_IDX + 1] : 'preserve-';

const LINK_COLOR = '#1057d6';
const TEXT_COLOR = '#0a0a0a';

// ─── MCP plumbing ────────────────────────────────────────────────────────────

async function mcpInit() {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: { protocolVersion: '2024-11-05', capabilities: {},
                clientInfo: { name: 'build-live-dom-canvas', version: '2.0' } },
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
  // Penpot's MCP returns plugin errors as a "Tool execution failed: ..." string,
  // not as isError true — surface it explicitly instead of silently parsing to {raw}.
  if (/^Tool execution failed/.test(payload)) {
    throw new Error(payload);
  }
  try { return JSON.parse(payload); } catch { return { raw: payload }; }
}

// ─── Font registry (fetched once from Penpot at startup) ─────────────────────

let FONT_INDEX = null;  // Map<lowercased-name, canonical-name>

async function loadFontIndex(sid) {
  const r = await mcpExec(sid, `return penpot.fonts.all.map(f => f.name);`);
  const names = r?.result || [];
  const map = new Map();
  for (const n of names) map.set(n.toLowerCase(), n);
  FONT_INDEX = map;
  return names.length;
}

const FONT_MISSES = new Map();  // requested → { count, fallback }

// Strip CSS variant suffixes that don't exist in Penpot's font names.
function normalizeFontFamily(family) {
  if (!family) return 'Work Sans';
  let base = family.replace(/['"]/g, '').split(',')[0].trim();
  // Common CSS-side suffixes that aren't part of the family name in Penpot.
  base = base.replace(/\s+(Variable|VF|GX|Italic|Caps|Sub|Roman|Display|Text)$/i, '').trim();
  // Drop CSS fallbacks that slip past split-on-comma when quoted (e.g. "Fraunces Variable, serif")
  base = base.replace(/\s+(serif|sans-serif|monospace|cursive|fantasy|system-ui)$/i, '').trim();
  if (!base) return 'Work Sans';
  if (!FONT_INDEX) return base;
  const exact = FONT_INDEX.get(base.toLowerCase());
  if (exact) return exact;
  // Try progressively-shorter prefixes: "Fraunces Pro Variable" → "Fraunces Pro" → "Fraunces"
  const tokens = base.split(/\s+/);
  for (let i = tokens.length - 1; i >= 1; i--) {
    const candidate = tokens.slice(0, i).join(' ');
    const hit = FONT_INDEX.get(candidate.toLowerCase());
    if (hit) return hit;
  }
  const miss = FONT_MISSES.get(base) || { count: 0, fallback: 'Work Sans' };
  miss.count++;
  FONT_MISSES.set(base, miss);
  return 'Work Sans';
}

// Probe Google Fonts for a family — used after a build to tell the user which
// missing families *could* be installed if they wanted higher fidelity.
async function googleFontsHas(name) {
  try {
    const url = `https://fonts.googleapis.com/css2?family=${encodeURIComponent(name)}&display=swap`;
    const r = await fetch(url, { signal: AbortSignal.timeout(5_000),
                                  headers: { 'user-agent': 'Mozilla/5.0' } });
    return r.ok;
  } catch { return false; }
}

// Poll the active page name until it matches one of `wantNames`, up to `maxMs`.
async function waitForCurrentPage(sid, wantNames, maxMs = 2000) {
  const names = Array.isArray(wantNames) ? wantNames : [wantNames];
  const deadline = Date.now() + maxMs;
  while (Date.now() < deadline) {
    const r = await mcpExec(sid, `return penpot.currentPage.name;`);
    if (names.includes(r?.result)) return r.result;
    await new Promise(res => setTimeout(res, 150));
  }
  throw new Error(`currentPage did not become one of [${names.join(', ')}] within ${maxMs}ms`);
}

// ─── DOM extraction (browser context) ────────────────────────────────────────

const DOM_HARVEST_FN = () => {
  const root = document.body;
  // pre/code added so monospace blocks land on the canvas.
  const SELECTOR_BLOCK   = 'h1,h2,h3,h4,h5,h6,p,li,blockquote,dd,dt,td,th,figcaption,pre,code';
  const SELECTOR_CONTROL = 'input,textarea,select';

  const isVisible = (el) => {
    const r = el.getBoundingClientRect();
    if (r.width === 0 || r.height === 0) return false;
    const s = window.getComputedStyle(el);
    if (s.visibility === 'hidden' || s.display === 'none') return false;
    if (parseFloat(s.opacity) === 0) return false;
    return true;
  };

  const isTransparent = (col) => {
    if (!col) return true;
    if (col === 'transparent') return true;
    const m = col.match(/rgba?\(\s*\d+\s*,\s*\d+\s*,\s*\d+(?:\s*,\s*([\d.]+))?/);
    return !!(m && m[1] !== undefined && parseFloat(m[1]) === 0);
  };

  const captureText = (el, kind, extra = {}) => {
    const r = el.getBoundingClientRect();
    const s = window.getComputedStyle(el);
    // textContent is cheap; reserve innerText for capture, not the visibility precheck
    const text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    if (!text && kind !== 'control' && kind !== 'image') return null;
    return {
      kind,
      text: text.slice(0, 500),
      tag: el.tagName.toLowerCase(),
      x: r.left + window.scrollX,
      y: r.top + window.scrollY,
      w: r.width, h: r.height,
      fontSize:   parseFloat(s.fontSize) || 16,
      fontWeight: s.fontWeight || '400',
      fontFamily: s.fontFamily || 'system-ui',
      lineHeight: s.lineHeight,
      color:      s.color || '',
      textAlign:  s.textAlign || 'left',
      ...extra,
    };
  };

  const out = [];

  // Reason: <code> and <pre> are in SELECTOR_BLOCK to catch standalone code
  // blocks, but inline <code> inside <li>/<p>/<h*> would double-capture and
  // visually mash on top of its parent. Skip nested instances.
  const NESTED_BLOCK_ANCESTORS = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,dd,dt,td,th,figcaption';
  for (const el of root.querySelectorAll(SELECTOR_BLOCK)) {
    if (!isVisible(el)) continue;
    if (!(el.textContent || '').trim()) continue;
    const tag = el.tagName.toLowerCase();
    if ((tag === 'code' || tag === 'pre') && el.parentElement?.closest(NESTED_BLOCK_ANCESTORS)) continue;
    const cap = captureText(el, /^h[1-6]$/.test(tag) ? 'heading' : 'paragraph');
    if (!cap) continue;
    // (e) List markers: <ul>/<ol> children don't render their bullet/number into
    // the DOM as a text node — Penpot would render a list item with no bullet.
    // Prefix a glyph so the canvas hints at "this is a list line".
    if (tag === 'li' && el.parentElement) {
      const p = el.parentElement.tagName.toLowerCase();
      if (p === 'ul') cap.text = '• ' + cap.text;
      else if (p === 'ol') cap.text = '– ' + cap.text;
    }
    out.push(cap);
  }

  // Hyperlinks. Track each link's host paragraph so we can de-overlap below.
  const linkItems = [];
  for (const el of root.querySelectorAll('a[href]')) {
    if (!isVisible(el)) continue;
    const href = el.getAttribute('href') || '';
    const c = captureText(el, 'link', {
      href,
      external: /^https?:\/\//.test(href),
      target: el.getAttribute('target') || '',
    });
    if (!c) continue;
    linkItems.push(c);
    out.push(c);
  }

  for (const el of root.querySelectorAll('button')) {
    if (!isVisible(el)) continue;
    const c = captureText(el, 'button');
    if (c) out.push(c);
  }

  for (const el of root.querySelectorAll(SELECTOR_CONTROL)) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const s = window.getComputedStyle(el);
    out.push({
      kind: 'control',
      text: el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.tagName.toLowerCase(),
      tag: el.tagName.toLowerCase(),
      x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height,
      fontSize: parseFloat(s.fontSize) || 14, fontWeight: '400', fontFamily: 'system-ui',
      color: s.color || '#1a1a1a',
    });
  }

  for (const el of root.querySelectorAll('img')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    out.push({
      kind: 'image', text: '', tag: 'img',
      src: el.getAttribute('src') || '',
      alt: el.getAttribute('alt') || '',
      x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height,
    });
  }

  // (a) Lines — three sources: <hr>, line-shaped divs (1-4px in one axis,
  // ≥20px in the other) with a non-transparent background, and elements whose
  // computed style has a top/bottom border that visibly draws a line.
  const seenLineKey = new Set();
  const pushLine = (x, y, w, h, color) => {
    if (w < 4 && h < 4) return;
    if (isTransparent(color)) return;
    // Reason: collapse coplanar lines that differ only in 1-2 px height (e.g. a
    // background-as-line at 2px plus a border-top at 1px on the same row). Round
    // major axis but drop minor — the rendered line looks the same either way.
    const key = `line:${Math.round(x)}:${Math.round(y)}:${Math.round(Math.max(w, h))}`;
    if (seenLineKey.has(key)) return;
    seenLineKey.add(key);
    out.push({ kind: 'line', x, y, w, h, color, text: '' });
  };
  for (const el of root.querySelectorAll('hr')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const s = window.getComputedStyle(el);
    const c = s.borderTopColor && !isTransparent(s.borderTopColor)
      ? s.borderTopColor
      : (s.backgroundColor && !isTransparent(s.backgroundColor) ? s.backgroundColor : '#cccccc');
    pushLine(r.left + window.scrollX, r.top + window.scrollY,
             Math.max(1, r.width), Math.max(1, r.height), c);
  }
  for (const el of root.querySelectorAll('div,span,section,article,aside,header,footer,nav,main')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const s = window.getComputedStyle(el);
    // Background-as-line (the .hero-rule pattern)
    const lineW = r.width, lineH = r.height;
    const isLineShape = (lineW >= 20 && lineH <= 4) || (lineH >= 20 && lineW <= 4);
    if (isLineShape && !isTransparent(s.backgroundColor)) {
      pushLine(r.left + window.scrollX, r.top + window.scrollY, lineW, lineH, s.backgroundColor);
    }
    // Border-as-line (decorative section dividers)
    const btw = parseFloat(s.borderTopWidth) || 0;
    if (btw >= 1 && s.borderTopStyle !== 'none' && !isTransparent(s.borderTopColor) && r.width >= 40) {
      pushLine(r.left + window.scrollX, r.top + window.scrollY, r.width, btw, s.borderTopColor);
    }
    const bbw = parseFloat(s.borderBottomWidth) || 0;
    if (bbw >= 1 && s.borderBottomStyle !== 'none' && !isTransparent(s.borderBottomColor) && r.width >= 40) {
      pushLine(r.left + window.scrollX, r.top + window.scrollY + r.height - bbw, r.width, bbw, s.borderBottomColor);
    }
  }

  // (b) SVGs — placeholder rectangles. Skip SVGs nested inside elements we
  // already captured (typical icon-in-link case) so we don't double-draw.
  for (const el of root.querySelectorAll('svg')) {
    if (!isVisible(el)) continue;
    if (el.closest('a,button,li')) continue;
    const r = el.getBoundingClientRect();
    out.push({
      kind: 'svg',
      text: '',
      viewBox: el.getAttribute('viewBox') || '',
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
    });
  }

  // (c) Paragraph/link de-overlap. Unified rule: any inline link whose text
  // is part of a surviving paragraph gets suppressed — the paragraph already
  // renders those words at the link's screen position, so two shapes at the
  // same spot would mash. Exception: when a single link covers >70% of the
  // paragraph, the link IS the content; drop the paragraph instead.
  const linkTexts = new Set(linkItems.map(l => l.text));
  const linksToSuppress = new Set();
  const droppedParagraphs = new Set();
  const paragraphs = out.filter(i => i && i.kind === 'paragraph');
  for (const p of paragraphs) {
    if (linkTexts.has(p.text)) { droppedParagraphs.add(p); continue; }
    const contained = linkItems.filter(l => p.text.includes(l.text) && l.text.length >= 4);
    if (contained.length === 1
        && contained[0].text.length / Math.max(1, p.text.length) > 0.7) {
      droppedParagraphs.add(p);
    } else if (contained.length >= 1) {
      for (const l of contained) linksToSuppress.add(l);
    }
  }

  // (f) Containing-block collapse. If paragraph A's text contains B's text AND
  // they share most of their on-screen area, A is the wrapper block — drop A,
  // keep B. Uses overlap-ratio (rather than strict bbox containment) because
  // margin-collapse, negative margins, and CSS-Grid spans make inner text
  // routinely escape the parent's geometric box by a few px.
  //
  // Known limitation: in CSS multi-column layouts (`columns: N`), every <p>'s
  // getBoundingClientRect() spans the full column-box even when the rendered
  // text only fills one column. This produces overlapping bboxes for sibling
  // <p>s that DON'T visually overlap on the live page. The de-overlap rule
  // doesn't fire (their text isn't a parent-child substring), so those shapes
  // stack on the canvas. Affects the home page's about-practice and the
  // publication-list sections.
  const stripMarker = (t) => (t || '').replace(/^[•–]\s*/, '');
  const overlapRatio = (a, b) => {
    const dx = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
    const dy = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y);
    if (dx <= 0 || dy <= 0) return 0;
    return (dx * dy) / Math.min(a.w * a.h, b.w * b.h);
  };
  const droppedOuter = new Set();
  for (const a of paragraphs) {
    for (const b of paragraphs) {
      if (a === b || droppedOuter.has(a) || droppedOuter.has(b)) continue;
      if (a.text.length <= b.text.length) continue;
      const bCore = stripMarker(b.text);
      if (a.text.includes(bCore) && overlapRatio(a, b) > 0.5) {
        droppedOuter.add(a);
        break;
      }
    }
  }

  const filtered = [];
  for (const item of out) {
    if (!item) continue;
    if (droppedParagraphs.has(item)) continue;
    if (droppedOuter.has(item)) continue;
    if (item.kind === 'link' && linksToSuppress.has(item)) continue;
    filtered.push(item);
  }

  const seen = new Set();
  const deduped = [];
  for (const item of filtered) {
    if (!item) continue;
    const key = `${item.kind}:${Math.round(item.x)}:${Math.round(item.y)}:${(item.text || '').slice(0, 40)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    deduped.push(item);
  }

  return {
    docWidth:  document.documentElement.scrollWidth,
    docHeight: document.documentElement.scrollHeight,
    items: deduped,
  };
};

// ─── Page harvest (Node side) ────────────────────────────────────────────────

async function harvestPage(ctx, page) {
  const tab = await ctx.newPage();
  const url = `${PORTFOLIO_URL}${page.path}`;
  await tab.goto(url, { waitUntil: 'networkidle', timeout: 30_000 });
  // Wait for fonts so getBoundingClientRect() returns post-font-swap geometry.
  // Variable fonts (Fraunces) change line metrics on load and produce mid-flight
  // overlaps if measured too early.
  await tab.waitForFunction(() => document.fonts && document.fonts.status === 'loaded',
                            { timeout: 4_000 }).catch(() => {});
  await tab.waitForTimeout(1500);
  const harvest = await tab.evaluate(DOM_HARVEST_FN);
  await tab.close();
  return { ...harvest, page };
}

// ─── Colour helpers ──────────────────────────────────────────────────────────

// Returns { color: '#rrggbb', alpha: 0..1 } or null on parse-fail / fully transparent.
function parseRgb(rgb) {
  const m = (rgb || '').match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?/);
  if (!m) return null;
  const alpha = m[4] !== undefined ? parseFloat(m[4]) : 1;
  if (alpha === 0) return null;
  return {
    color: '#' + [m[1], m[2], m[3]].map(n => Number(n).toString(16).padStart(2, '0')).join(''),
    alpha,
  };
}

// Legacy callers that only want the hex string.
function rgbToHex(rgb) {
  const p = parseRgb(rgb);
  return p ? p.color : null;
}

function resolveColor(item, isLink) {
  if (isLink) return LINK_COLOR;
  const raw = item.color || '';
  const fromRgb = /^rgb/.test(raw) ? rgbToHex(raw) : (raw || null);
  return fromRgb || TEXT_COLOR;
}

// ─── shape JS generation ─────────────────────────────────────────────────────
// Coordinates are pre-translated into board-local space at template-build time
// — no runtime `boardX/boardY` needed by the emitted snippet.

function chunk(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

function shapeJs(item, boardX, boardY) {
  const safeText  = (item.text || '').slice(0, 500);
  const textJson  = JSON.stringify(safeText);
  const family    = normalizeFontFamily(item.fontFamily);
  const familyJs  = JSON.stringify(family || 'Work Sans');
  const weightMap = { normal: '400', bold: '700', bolder: '700', lighter: '300' };
  const weight    = String(weightMap[item.fontWeight] || item.fontWeight || '400').match(/\d+/)?.[0] || '400';
  const fontSize  = Math.max(8, Math.round(item.fontSize || 16));
  const w         = Math.max(8, Math.round(item.w));
  const h         = Math.max(8, Math.round(item.h));
  const x         = Math.round(item.x) - boardX;
  const y         = Math.round(item.y) - boardY;

  if (item.kind === 'control') {
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify('control: ' + item.tag)};
  r.resize(${w}, ${h});
  r.fills = [{ fillColor: '#ffffff' }];
  r.strokes = [{ strokeColor: '#cccccc', strokeStyle: 'solid', strokeAlignment: 'inner', strokeWidth: 1 }];
  r.borderRadius = 4;
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  const t = penpot.createText(${textJson});
  t.fontFamily = ${familyJs};
  t.fontSize = '${fontSize}';
  t.fontWeight = '400';
  t.fills = [{ fillColor: '#888888' }];
  t.growType = 'auto-height';
  t.resize(${Math.max(8, w - 16)}, ${Math.max(16, fontSize + 4)});
  board.appendChild(t);
  t.x = ${x + 8}; t.y = ${y + 4};
  return { kind: 'control', id: r.id };
}`;
  }

  if (item.kind === 'image') {
    const label = 'img: ' + ((item.alt || item.src || '').slice(0, 80));
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(label)};
  r.fills = [{ fillColor: '#eeeeee' }];
  r.strokes = [{ strokeColor: '#cccccc', strokeStyle: 'dashed', strokeAlignment: 'inner', strokeWidth: 1 }];
  r.resize(${w}, ${h});
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  return { kind: 'image', id: r.id };
}`;
  }

  if (item.kind === 'line') {
    // Preserve alpha — rgba(217,212,203,0.3) rendered as opaque #d9d4cb was the
    // 9/10 audit's only Important finding; lines were silently 100% opaque.
    const parsed = item.color && /^rgb/.test(item.color) ? parseRgb(item.color) : null;
    const lineColor = parsed?.color || (item.color && !/^rgb/.test(item.color) ? item.color : '#cccccc');
    const lineAlpha = parsed?.alpha ?? 1;
    const lw = Math.max(1, Math.round(item.w));
    const lh = Math.max(1, Math.round(item.h));
    const fillJs = lineAlpha < 1
      ? `[{ fillColor: ${JSON.stringify(lineColor)}, fillOpacity: ${lineAlpha} }]`
      : `[{ fillColor: ${JSON.stringify(lineColor)} }]`;
    return `
{
  const r = penpot.createRectangle();
  r.name = 'line';
  r.fills = ${fillJs};
  r.strokes = [];
  r.resize(${lw}, ${lh});
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  return { kind: 'line', id: r.id };
}`;
  }

  if (item.kind === 'svg') {
    const label = 'svg: ' + (item.viewBox || '');
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(label.slice(0, 80))};
  r.fills = [{ fillColor: '#f5f5f5' }];
  r.strokes = [{ strokeColor: '#888888', strokeStyle: 'dashed', strokeAlignment: 'inner', strokeWidth: 1 }];
  r.resize(${w}, ${h});
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  return { kind: 'svg', id: r.id };
}`;
  }

  // Text-like
  const isLink     = item.kind === 'link';
  const color      = resolveColor(item, isLink);
  const namePrefix = isLink ? `link: ${item.href || ''}` : item.kind;
  const initialH   = Math.max(h, fontSize + 4);

  return `
{
  const t = penpot.createText(${textJson});
  t.fontFamily = ${familyJs};
  t.fontSize = '${fontSize}';
  t.fontWeight = '${weight}';
  t.fills = [{ fillColor: ${JSON.stringify(color)} }];
  t.growType = 'auto-height';
  t.resize(${w}, ${initialH});
  ${isLink ? `t.textDecoration = 'underline';` : ''}
  t.name = ${JSON.stringify(namePrefix.slice(0, 120))};
  board.appendChild(t);
  t.x = ${x};
  t.y = ${y};
  return { kind: ${JSON.stringify(item.kind)}, id: t.id };
}`;
}

// ─── Board preparation + shape creation ──────────────────────────────────────

async function prepareBoard(sid, boardName, docWidth, docHeight, resetBoard, preservePrefix) {
  const preserveJs = JSON.stringify(preservePrefix || '');
  const code = `
const cur = penpot.currentPage;
let board = cur.findShapes({ name: ${JSON.stringify(boardName)} }).find(s => s.type === 'board');
if (!board) {
  // Reason: stack new boards side-by-side instead of piling them at (0,0).
  const ROOT_ID = '00000000-0000-0000-0000-000000000000';
  const topBoards = cur.findShapes().filter(s => s.type === 'board' && s.id !== ROOT_ID && s.parent && s.parent.id === ROOT_ID);
  const nextX = topBoards.reduce((m, s) => Math.max(m, (s.x || 0) + (s.width || 0) + 120), 0);
  board = penpot.createBoard();
  board.name = ${JSON.stringify(boardName)};
  board.x = nextX; board.y = 0;
}
const boardX = board.x;
const boardY = board.y;
const newW = Math.max(800, Math.round(${docWidth}));
const newH = Math.max(600, Math.round(${docHeight}));

// Capture children BEFORE resize — geometry needs to be remembered relative to
// the pre-resize board so we can reposition the screenshot correctly.
const childrenBefore = cur.findShapes().filter(s => s.parent && s.parent.id === board.id);
const fillImgFinder = s => s.type === 'rectangle' && s.fills && s.fills.some(f => f && (f.fillImage || f.fillImageUrl || f.fillImageData));
let screenshot = childrenBefore.find(fillImgFinder);

board.resize(newW, newH);
board.clipContent = true;
board.fills = [{ fillColor: '#ffffff' }];

// Resize the screenshot child to cover the new board (preserves backdrop intent).
if (screenshot) {
  screenshot.resize(newW, newH);
  screenshot.x = 0; screenshot.y = 0;
}

// Adopt any top-level orphaned fill-image rect that looks like our screenshot.
// Match by fillImage presence + parent==root, not by literal "<name>.png" name.
const orphans = cur.findShapes()
  .filter(s => s.parent && s.parent.id === '00000000-0000-0000-0000-000000000000')
  .filter(s => fillImgFinder(s));
for (const o of orphans) {
  if (!screenshot) {
    board.appendChild(o);
    o.resize(newW, newH);
    o.x = 0; o.y = 0;
    screenshot = o;
  } else {
    o.remove();
  }
}

// Wipe every other previously-generated child. Keep the screenshot (or remove it
// entirely when --reset-board is set).
const preservePrefix = ${preserveJs};
const boardChildren = cur.findShapes().filter(s => s.parent && s.parent.id === board.id);
// Reason: --preserve-prefix lets users keep hand-added shapes across rebuilds.
const preserved = boardChildren.filter(s => preservePrefix && s.name && s.name.startsWith(preservePrefix));
const toRemove = boardChildren
  .filter(s => !screenshot || s.id !== screenshot.id)
  .filter(s => !(preservePrefix && s.name && s.name.startsWith(preservePrefix)));
let removed = 0;
for (const c of toRemove) { c.remove(); removed++; }
if (${resetBoard ? 'true' : 'false'} && screenshot) { screenshot.remove(); screenshot = null; removed++; }

// Fade the screenshot so the live shapes read on top.
if (screenshot) {
  screenshot.opacity = 0.08;
  screenshot.name = 'screenshot-backdrop';
}

return {
  boardId: board.id,
  boardX, boardY,
  w: board.width, h: board.height,
  screenshot: screenshot ? { id: screenshot.id, name: screenshot.name } : null,
  removed,
  preserved: preserved.length,
};
`;
  return mcpExec(sid, code);
}

async function createShapesInBatches(sid, boardId, boardX, boardY, items, batchSize) {
  const batches = chunk(items, batchSize);
  const results = [];
  for (const batch of batches) {
    const body = batch.map(item => `out.push((() => ${shapeJs(item, boardX, boardY)})());`).join('\n');
    const code = `
const cur = penpot.currentPage;
const board = cur.findShapes().find(s => s.id === ${JSON.stringify(boardId)});
if (!board) return { error: 'board not found' };
const out = [];
${body}
return { created: out.length, kinds: out.map(o => o && o.kind) };
`;
    try {
      results.push(await mcpExec(sid, code));
    } catch (e) {
      results.push({ error: e.message, batchSize: batch.length });
    }
  }
  return results;
}

// ─── Main ────────────────────────────────────────────────────────────────────

async function main() {
  const t0 = Date.now();
  console.log(`portfolio_local = ${PORTFOLIO_URL}`);
  console.log(`pages           = ${PAGES.map(p => p.name).join(', ')}`);
  console.log(`batch size      = ${BATCH_SIZE}${RESET_BOARD ? '   (reset-board)' : ''}`);
  console.log('');

  const sid = await mcpInit();

  // Load Penpot's font registry so we can match what the portfolio CSS declares.
  const fontCount = await loadFontIndex(sid);
  console.log(`Loaded ${fontCount} Penpot fonts`);

  // Ensure Page 1 (or the first page if no page is literally named "Page 1") is active.
  const pageSwitch = await mcpExec(sid, `
const named = penpot.currentFile.pages.find(p => p.name === 'Page 1');
const target = named || penpot.currentFile.pages[0];
if (target && penpot.currentPage.id !== target.id) penpot.openPage(target);
return { switched: penpot.currentPage.name, fallback: !named && target ? target.name : null };
`);
  const targetPageName = pageSwitch?.result?.switched;
  if (pageSwitch?.result?.fallback) {
    console.log(`WARN: no page named "Page 1" — using "${pageSwitch.result.fallback}" instead`);
  }
  await waitForCurrentPage(sid, [targetPageName, 'Page 1']);

  console.log('Launching headless Chromium...');
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

  // Parallel DOM harvest — pages are read-only against the dev server.
  const tH0 = Date.now();
  const harvests = await Promise.all(PAGES.map(p => harvestPage(ctx, p)));
  console.log(`Harvested ${harvests.length} pages in ${Date.now() - tH0}ms`);
  console.log('');

  // Sequential Penpot writes — MCP plugin is single-threaded.
  for (const h of harvests) {
    const page = h.page;
    const pT0 = Date.now();
    console.log(`── ${page.name} ──────────────────────────────────────────`);
    console.log(`  DOM: ${h.docWidth}x${h.docHeight}, ${h.items.length} items`);
    const breakdown = h.items.reduce((a, it) => (a[it.kind] = (a[it.kind] || 0) + 1, a), {});
    console.log(`  kinds: ${Object.entries(breakdown).map(([k, v]) => `${k}=${v}`).join(' ')}`);

    const prep = await prepareBoard(sid, page.name, h.docWidth, h.docHeight, RESET_BOARD, PRESERVE_PREFIX);
    if (!prep?.result) { console.log(`  prepareBoard failed: ${JSON.stringify(prep)}`); continue; }
    const { boardId, boardX, boardY, removed, preserved, screenshot } = prep.result;
    console.log(`  board ${boardId.slice(-12)}  (anchor ${boardX},${boardY}; removed ${removed} stale children; preserved ${preserved || 0}; backdrop=${screenshot ? screenshot.name : 'none'})`);

    const batches = await createShapesInBatches(sid, boardId, boardX, boardY, h.items, BATCH_SIZE);
    const totalCreated = batches.reduce((a, b) => a + (b?.result?.created || 0), 0);
    const errors = batches.filter(b => b?.error || b?.result?.error);
    console.log(`  created: ${totalCreated} shapes in ${batches.length} batches (${errors.length} errors)  [${Date.now() - pT0}ms]`);
    for (const e of errors.slice(0, 2)) console.log(`    err: ${JSON.stringify(e).slice(0, 240)}`);
  }

  await browser.close();

  if (FONT_MISSES.size > 0) {
    console.log('');
    console.log('Font misses (not in Penpot, fell back to Work Sans):');
    for (const [name, info] of FONT_MISSES) {
      const onGoogle = await googleFontsHas(name);
      const hint = onGoogle
        ? `  → on Google Fonts: install with the Penpot UI (Profile → Fonts → Upload) or download from https://fonts.google.com/specimen/${encodeURIComponent(name.replace(/\s+/g, '+'))}`
        : `  → not on Google Fonts either`;
      console.log(`  ${name.padEnd(28)} ${String(info.count).padStart(3)} uses`);
      console.log(hint);
    }
  }

  console.log('');
  console.log(`Done in ${Date.now() - t0}ms.`);
}

main().catch(err => { console.error('build-live-dom-canvas error:', err.message); process.exit(1); });
