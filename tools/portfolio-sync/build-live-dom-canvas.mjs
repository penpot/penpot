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

const LINK_COLOR = '#b33a1a';
const TEXT_COLOR = '#1a1a1a';
const BOARD_BG   = '#faf8f3'; // Reason: portfolio uses var(--bg) = #FAF8F3 (warm cream), not pure white

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

  // Detect a multi-column ancestor (CSS `column-count: N` or `column-width: ...`).
  // Element.getBoundingClientRect() bundles every column-fragment into one box,
  // which is geometrically wrong for placement on a Penpot canvas that doesn't
  // model columns. getClientRects() returns the fragments individually.
  const multiColumnAncestor = (el) => {
    let p = el.parentElement;
    while (p) {
      const s = window.getComputedStyle(p);
      if ((s.columnCount && s.columnCount !== 'auto') ||
          (s.columnWidth && s.columnWidth !== 'auto')) return true;
      p = p.parentElement;
    }
    return false;
  };

  // Returns: array of {x,y,w,h} rects representing where the element actually
  // renders on the page. For most elements this is a single rect equal to
  // getBoundingClientRect. For multi-column-flowed elements it's one per
  // column fragment (cluster getClientRects() by x).
  const fragmentRects = (el) => {
    if (!multiColumnAncestor(el)) {
      const r = el.getBoundingClientRect();
      return [{ x: r.left, y: r.top, w: r.width, h: r.height }];
    }
    const lines = Array.from(el.getClientRects()).filter(r => r.width > 0 && r.height > 0);
    if (lines.length <= 1) {
      const r = el.getBoundingClientRect();
      return [{ x: r.left, y: r.top, w: r.width, h: r.height }];
    }
    // Cluster lines by left-edge within 30px tolerance (one cluster per column).
    const clusters = [];
    for (const r of lines) {
      let c = clusters.find(c => Math.abs(c.x - r.left) < 30);
      if (!c) { c = { x: r.left, minY: r.top, maxY: r.bottom, maxX: r.right }; clusters.push(c); }
      else {
        c.minY = Math.min(c.minY, r.top);
        c.maxY = Math.max(c.maxY, r.bottom);
        c.maxX = Math.max(c.maxX, r.right);
      }
    }
    return clusters.map(c => ({ x: c.x, y: c.minY, w: c.maxX - c.x, h: c.maxY - c.minY }));
  };

  // Always returns an array (may be empty). One entry per visual fragment —
  // for non-multi-column elements that's a single entry, matching the prior
  // single-shape behaviour. Multi-column flowed elements emit one entry per
  // column fragment.
  // Read CSS animation/transition off `el` and return a `cssAnimation` payload
  // or null. The Penpot canvas can't show motion; downstream renderers
  // (canvas-to-html.mjs) use this to draw a small marker dot in the shape's
  // top-right corner. The user's spec: animationName !== 'none' OR transition
  // !== 'all 0s ease 0s' (the browser default). We honor both.
  const DEFAULT_TRANSITION = 'all 0s ease 0s';
  const readAnimation = (el) => {
    const s = window.getComputedStyle(el);
    const hasAnim = s.animationName && s.animationName !== 'none';
    const hasTrans = s.transition && s.transition !== DEFAULT_TRANSITION;
    if (!hasAnim && !hasTrans) return null;
    return {
      name:     hasAnim ? s.animationName : 'transition',
      duration: hasAnim ? (s.animationDuration || '0s') : (s.transitionDuration || '0s'),
      delay:    hasAnim ? (s.animationDelay    || '0s') : (s.transitionDelay    || '0s'),
    };
  };

  const captureText = (el, kind, extra = {}) => {
    const s = window.getComputedStyle(el);
    const text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
    if (!text && kind !== 'control' && kind !== 'image') return [];
    const frags = fragmentRects(el);
    const anim = readAnimation(el);
    return frags.map(r => ({
      kind,
      text: text.slice(0, 500),
      tag: el.tagName.toLowerCase(),
      x: r.x + window.scrollX,
      y: r.y + window.scrollY,
      w: r.w, h: r.h,
      fontSize:   parseFloat(s.fontSize) || 16,
      fontWeight: s.fontWeight || '400',
      fontFamily: s.fontFamily || 'system-ui',
      lineHeight: s.lineHeight,
      color:      s.color || '',
      textAlign:  s.textAlign || 'left',
      ...(anim ? { cssAnimation: anim } : {}),
      ...extra,
    }));
  };

  const out = [];

  // ── New media passes (videos, iframes, CSS background-images, pictures) ──
  // Reason: these run BEFORE the text walk so bg-image rectangles emit FIRST
  // in `out`; Penpot's render order is creation order, so text shapes that
  // come from later passes correctly stack on top.

  // CSS background-image — every visible element whose computed style has a
  // background-image URL (not a gradient). Skip if the URL is a gradient
  // (linear-gradient, radial-gradient, conic-gradient) since those aren't
  // real images and would emit useless empty bg-image placeholders.
  const seenBgKey = new Set();
  for (const el of root.querySelectorAll('*')) {
    if (!isVisible(el)) continue;
    const s = window.getComputedStyle(el);
    const bg = s.backgroundImage;
    if (!bg || bg === 'none') continue;
    // Reject anything that's purely gradient(s) — linear-gradient(...),
    // radial-gradient(...), conic-gradient(...), or layered combos thereof.
    if (/gradient\s*\(/i.test(bg) && !/url\s*\(/i.test(bg)) continue;
    // Extract the first url(...) — if there's no real url, skip.
    const urlMatch = bg.match(/url\(\s*(['"]?)([^'")]+)\1\s*\)/);
    if (!urlMatch) continue;
    const bgUrl = urlMatch[2];
    if (!bgUrl || bgUrl.startsWith('data:')) {
      // data: URIs are usually decorative SVG sprites — skip; treating them
      // as bg-image placeholders adds noise without information.
      continue;
    }
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) continue;
    const x = r.left + window.scrollX;
    const y = r.top + window.scrollY;
    // Dedupe coplanar bg-images that share the same URL + bbox (rare, but
    // a parent/child can both carry the same background-image via inheritance
    // in edge CSS cases).
    const key = `bg:${Math.round(x)}:${Math.round(y)}:${Math.round(r.width)}:${Math.round(r.height)}:${bgUrl}`;
    if (seenBgKey.has(key)) continue;
    seenBgKey.add(key);
    const animBg = readAnimation(el);
    out.push({
      kind: 'bg-image',
      text: '',
      tag: el.tagName.toLowerCase(),
      src: bgUrl,
      backgroundSize: s.backgroundSize || 'auto',
      backgroundPosition: s.backgroundPosition || 'center',
      x, y, w: r.width, h: r.height,
      ...(animBg ? { cssAnimation: animBg } : {}),
    });
  }

  // <video> — capture src/poster + playback attrs.
  for (const el of root.querySelectorAll('video')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    // <video> src may be on the element OR on an inner <source>.
    const src = el.getAttribute('src') ||
                el.querySelector('source')?.getAttribute('src') || '';
    const animV = readAnimation(el);
    out.push({
      kind: 'video',
      text: '', tag: 'video',
      src,
      poster: el.getAttribute('poster') || '',
      autoplay: el.hasAttribute('autoplay'),
      loop: el.hasAttribute('loop'),
      muted: el.hasAttribute('muted'),
      controls: el.hasAttribute('controls'),
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
      ...(animV ? { cssAnimation: animV } : {}),
    });
  }

  // <iframe> — capture as placeholder. Detect host and emit a richer label
  // for YouTube embeds (youtube.com/embed/<id>).
  for (const el of root.querySelectorAll('iframe')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const src = el.getAttribute('src') || '';
    let host = '';
    let label = '';
    try {
      const u = new URL(src, document.baseURI);
      host = u.host;
      if (/youtube\.com$/.test(host) || host === 'www.youtube.com' || host === 'youtube.com') {
        const m = u.pathname.match(/\/embed\/([\w-]+)/);
        if (m) label = `youtube embed: ${m[1]}`;
        else label = `youtube: ${u.pathname}`;
      } else if (/player\.vimeo\.com$/.test(host) || host === 'player.vimeo.com') {
        const m = u.pathname.match(/\/video\/(\d+)/);
        label = m ? `vimeo embed: ${m[1]}` : `vimeo: ${u.pathname}`;
      } else if (/codepen\.io$/.test(host)) {
        label = `codepen: ${u.pathname}`;
      } else if (/codesandbox\.io$/.test(host)) {
        label = `codesandbox: ${u.pathname}`;
      } else {
        label = `iframe: ${host}`;
      }
    } catch (_) {
      host = '';
      label = `iframe: ${src.slice(0, 60)}`;
    }
    const animI = readAnimation(el);
    out.push({
      kind: 'iframe',
      text: '', tag: 'iframe',
      src, host, label,
      allow: el.getAttribute('allow') || '',
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
      ...(animI ? { cssAnimation: animI } : {}),
    });
  }

  // <canvas> — capture as `kind: 'canvas'`. Probe getContext() for a WebGL
  // context to mark `hasWebGL`. Best-effort snapshot via toDataURL — WebGL
  // canvases without `preserveDrawingBuffer:true` typically throw or return
  // an empty buffer, so we wrap in try/catch and only pass a snapshot through
  // when it's clearly a real PNG. The consulting page's neural-starfield
  // surprisingly DOES allow toDataURL because the context was created with
  // preserveDrawingBuffer set; we still don't assume that's true elsewhere.
  for (const el of root.querySelectorAll('canvas')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    let hasWebGL = false;
    try {
      const gl = el.getContext('webgl2') || el.getContext('webgl') || el.getContext('experimental-webgl');
      if (gl) {
        // Reason: getContext('webgl') returns null AFTER a 2d context has been
        // established on the same canvas, but on a fresh probe it gives the
        // real GL context. instanceof check confirms it's not a 2d ctx.
        if ((typeof WebGLRenderingContext !== 'undefined' && gl instanceof WebGLRenderingContext) ||
            (typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext)) {
          hasWebGL = true;
        }
      }
    } catch (_) { /* unreachable in practice — just defensive */ }
    let snapshot = null;
    try {
      // Try toDataURL — for 2d canvases this always works; for WebGL it works
      // only if the context was created with preserveDrawingBuffer:true, OR
      // we caught it before the next composite. Either way: defensive.
      const url = el.toDataURL('image/png');
      if (url && url.length > 100 && url.startsWith('data:image/png')) snapshot = url;
    } catch (_) { /* swallow — WebGL canvases routinely throw here */ }
    const animC = readAnimation(el);
    out.push({
      kind: 'canvas',
      text: '',
      tag: 'canvas',
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
      hasWebGL,
      snapshot,  // data:image/png;base64,... or null
      label: el.id || el.getAttribute('aria-label') || '',
      ...(animC ? { cssAnimation: animC } : {}),
    });
  }

  // <lottie-player> + .lottie containers. Capture as `kind: 'lottie'` with the
  // src URL extracted from src/data-src. The Lottie JSON itself isn't loaded
  // by Penpot — render as a placeholder rectangle with the filename label.
  const seenLottieKey = new Set();
  const pushLottie = (el, srcAttr) => {
    if (!isVisible(el)) return;
    const r = el.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    const src = (srcAttr || '').trim();
    const key = `lottie:${Math.round(r.left)}:${Math.round(r.top)}:${src}`;
    if (seenLottieKey.has(key)) return;
    seenLottieKey.add(key);
    const animL = readAnimation(el);
    out.push({
      kind: 'lottie',
      text: '',
      tag: el.tagName.toLowerCase(),
      src,
      // Filename for the label — last path segment of src, sans query.
      filename: (() => {
        try {
          if (!src) return '';
          const u = new URL(src, document.baseURI);
          const parts = u.pathname.split('/').filter(Boolean);
          return parts[parts.length - 1] || u.host;
        } catch (_) { return src.split('/').pop() || src; }
      })(),
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
      ...(animL ? { cssAnimation: animL } : {}),
    });
  };
  for (const el of root.querySelectorAll('lottie-player')) {
    pushLottie(el, el.getAttribute('src') || el.getAttribute('data-src'));
  }
  for (const el of root.querySelectorAll('.lottie, [data-lottie]')) {
    // Skip <lottie-player> elements that ALSO happen to carry `.lottie` —
    // they were already captured above.
    if (el.tagName.toLowerCase() === 'lottie-player') continue;
    const src = el.getAttribute('data-src') || el.getAttribute('src') || el.getAttribute('data-lottie') || '';
    pushLottie(el, src);
  }

  // <picture> is intentionally NOT captured directly — its inner <img> already
  // gets the bbox and renders correctly. The <picture> element itself has no
  // rendering box. Verified by getBoundingClientRect returning 0×0 on Chromium.

  // Reason: <code> and <pre> are in SELECTOR_BLOCK to catch standalone code
  // blocks, but inline <code> inside <li>/<p>/<h*> would double-capture and
  // visually mash on top of its parent. Skip nested instances.
  const NESTED_BLOCK_ANCESTORS = 'p,li,h1,h2,h3,h4,h5,h6,blockquote,dd,dt,td,th,figcaption';
  // Stable per-source-element id so downstream rules can tell "two fragments
  // of the same <p>" apart from "two distinct paragraphs that happen to share
  // text". Used by the containing-block collapse rule below.
  let __srcId = 0;
  for (const el of root.querySelectorAll(SELECTOR_BLOCK)) {
    if (!isVisible(el)) continue;
    if (!(el.textContent || '').trim()) continue;
    const tag = el.tagName.toLowerCase();
    if ((tag === 'code' || tag === 'pre') && el.parentElement?.closest(NESTED_BLOCK_ANCESTORS)) continue;
    const caps = captureText(el, /^h[1-6]$/.test(tag) ? 'heading' : 'paragraph');
    const src = __srcId++;
    for (let i = 0; i < caps.length; i++) {
      const cap = caps[i];
      cap._src = src;
      // (e) List markers: <ul>/<ol> children don't render their bullet/number
      // into the DOM as a text node. Prefix ONLY the first fragment — the
      // rendered page only shows one bullet, at the start of the first column.
      if (i === 0 && tag === 'li' && el.parentElement) {
        const p = el.parentElement.tagName.toLowerCase();
        if (p === 'ul') cap.text = '• ' + cap.text;
        else if (p === 'ol') cap.text = '– ' + cap.text;
      }
      out.push(cap);
    }
  }

  // Hyperlinks. Track each link's host paragraph so we can de-overlap below.
  const linkItems = [];
  for (const el of root.querySelectorAll('a[href]')) {
    if (!isVisible(el)) continue;
    const href = el.getAttribute('href') || '';
    const caps = captureText(el, 'link', {
      href,
      external: /^https?:\/\//.test(href),
      target: el.getAttribute('target') || '',
    });
    const src = __srcId++;
    for (const c of caps) { c._src = src; linkItems.push(c); out.push(c); }
  }

  for (const el of root.querySelectorAll('button')) {
    if (!isVisible(el)) continue;
    const src = __srcId++;
    for (const c of captureText(el, 'button')) { c._src = src; out.push(c); }
  }

  // (g) Leaf-text containers — <div>/<span>/<em>/<strong>/etc. with their own
  // text content. The portfolio's SectionLabel renders as
  // `<div class="section-label">§ 00 / Front Matter</div>`, which has no
  // semantic block tag and was silently dropped. Capture only LEAF text
  // containers: elements with a direct child text node that aren't already
  // covered by an ancestor we walked. Avoids exploding the shape count from
  // wrapper divs.
  const hasDirectText = (el) => {
    for (const n of el.childNodes) {
      if (n.nodeType === 3 /* TEXT_NODE */ && (n.textContent || '').trim().length > 1) return true;
    }
    return false;
  };
  // Anything matched by these selectors already captured the text; skip nested
  // descendants to avoid double-rendering.
  const LEAF_SKIP_ANCESTORS = NESTED_BLOCK_ANCESTORS + ',a,button,pre,code';
  const LEAF_TAGS = 'div,span,em,strong,small,mark,kbd,samp,abbr,cite,time,address,label';
  for (const el of root.querySelectorAll(LEAF_TAGS)) {
    if (!isVisible(el)) continue;
    if (!hasDirectText(el)) continue;
    if (el.parentElement?.closest(LEAF_SKIP_ANCESTORS)) continue;
    const caps = captureText(el, 'paragraph');
    const src = __srcId++;
    for (const c of caps) { c._src = src; out.push(c); }
  }

  for (const el of root.querySelectorAll(SELECTOR_CONTROL)) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const s = window.getComputedStyle(el);
    const animCt = readAnimation(el);
    out.push({
      kind: 'control',
      text: el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.tagName.toLowerCase(),
      tag: el.tagName.toLowerCase(),
      x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height,
      fontSize: parseFloat(s.fontSize) || 14, fontWeight: '400', fontFamily: 'system-ui',
      color: s.color || '#1a1a1a',
      ...(animCt ? { cssAnimation: animCt } : {}),
    });
  }

  for (const el of root.querySelectorAll('img')) {
    if (!isVisible(el)) continue;
    const r = el.getBoundingClientRect();
    const animIm = readAnimation(el);
    out.push({
      kind: 'image', text: '', tag: 'img',
      src: el.getAttribute('src') || '',
      alt: el.getAttribute('alt') || '',
      x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height,
      ...(animIm ? { cssAnimation: animIm } : {}),
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
    const animS = readAnimation(el);
    out.push({
      kind: 'svg',
      text: '',
      viewBox: el.getAttribute('viewBox') || '',
      x: r.left + window.scrollX, y: r.top + window.scrollY,
      w: r.width, h: r.height,
      ...(animS ? { cssAnimation: animS } : {}),
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
      // Reason: two fragments of the same multi-column element share _src.
      // Without this guard the rule could collapse one fragment into another
      // and undo the whole getClientRects path. The text-length check below
      // already covers the same-text case, but be explicit.
      if (a._src !== undefined && a._src === b._src) continue;
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

  // Capture the page's background colour at harvest time. Prefer <body>'s
  // computed background-color, fall back to <html>, and finally white when
  // both are transparent. Convert rgb()/rgba() to #rrggbb so downstream code
  // can drop it straight into a Penpot fill (which expects hex). This is what
  // lets the consulting page (near-black starfield + light text) render with
  // legible contrast — a hardcoded cream board fill swallows the light text.
  // Reason: reuse the in-scope `isTransparent` helper defined above; only
  // define a local rgb→hex since there isn't one in the browser context.
  const _rgbToHex = (rgb) => {
    const m = (rgb || '').match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);
    if (!m) return null;
    const h = (n) => Number(n).toString(16).padStart(2, '0');
    return '#' + h(m[1]) + h(m[2]) + h(m[3]);
  };
  const bodyBg = window.getComputedStyle(document.body).backgroundColor;
  const htmlBg = window.getComputedStyle(document.documentElement).backgroundColor;
  let pageBg = '#ffffff';
  if (!isTransparent(bodyBg))      pageBg = _rgbToHex(bodyBg) || '#ffffff';
  else if (!isTransparent(htmlBg)) pageBg = _rgbToHex(htmlBg) || '#ffffff';

  return {
    docWidth:  document.documentElement.scrollWidth,
    docHeight: document.documentElement.scrollHeight,
    pageBg,
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

function shapeJs(item) {
  const safeText  = (item.text || '').slice(0, 500);
  const textJson  = JSON.stringify(safeText);
  const family    = normalizeFontFamily(item.fontFamily);
  const familyJs  = JSON.stringify(family || 'Work Sans');
  const weightMap = { normal: '400', bold: '700', bolder: '700', lighter: '300' };
  const weight    = String(weightMap[item.fontWeight] || item.fontWeight || '400').match(/\d+/)?.[0] || '400';
  const fontSize  = Math.max(8, Math.round(item.fontSize || 16));
  const w         = Math.max(8, Math.round(item.w));
  const h         = Math.max(8, Math.round(item.h));
  // Reason: Penpot's t.x/t.y after appendChild are BOARD-LOCAL — the board's
  // canvas anchor (boardX/boardY) is irrelevant here. Subtracting it would put
  // consulting/blog shapes at negative local x and render them on top of the
  // home board's space. The DOM bbox is already in the per-page viewport's
  // own (0,0)-origin coordinate space, which is exactly what we want for the
  // board-local position.
  const x         = Math.round(item.x);
  const y         = Math.round(item.y);

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

  if (item.kind === 'video') {
    // Reason: Penpot's plugin API exposes `penpot.uploadMediaUrl(name, url)`
    // which returns a fillImage descriptor. Try to use the poster as the
    // rectangle's fill; if upload fails (CORS / 404 / unreachable), fall
    // back to a dark grey rect with a centered "▶ video" label.
    const label = 'video: ' + ((item.src || '').slice(0, 80));
    const posterJs = item.poster ? JSON.stringify(item.poster) : 'null';
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(label)};
  r.resize(${w}, ${h});
  let fillSet = false;
  const poster = ${posterJs};
  if (poster) {
    try {
      const img = await penpot.uploadMediaUrl('video-poster', poster);
      if (img) { r.fills = [{ fillOpacity: 1, fillImage: img }]; fillSet = true; }
    } catch (e) { /* swallow — fall back to grey */ }
  }
  if (!fillSet) {
    r.fills = [{ fillColor: '#2a2a2a' }];
  }
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  // Centered "▶ video" overlay so the placeholder is recognisable on canvas.
  const t = penpot.createText('▶ video');
  t.fontFamily = 'Work Sans';
  t.fontSize = '${Math.max(12, Math.min(24, Math.round(Math.min(w, h) / 8)))}';
  t.fontWeight = '600';
  t.fills = [{ fillColor: '#ffffff' }];
  t.growType = 'auto-height';
  t.resize(${Math.max(80, Math.round(w / 2))}, 32);
  t.name = 'video-label';
  board.appendChild(t);
  t.x = ${x + Math.round(w / 2) - Math.max(40, Math.round(w / 4))};
  t.y = ${y + Math.round(h / 2) - 16};
  return { kind: 'video', id: r.id };
}`;
  }

  if (item.kind === 'iframe') {
    const labelText = (item.label || ('iframe: ' + (item.host || ''))).slice(0, 80);
    const nameLabel = 'iframe: ' + ((item.src || '').slice(0, 80));
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(nameLabel)};
  r.fills = [{ fillColor: '#f0f0f0' }];
  r.strokes = [{ strokeColor: '#888888', strokeStyle: 'dashed', strokeAlignment: 'inner', strokeWidth: 1 }];
  r.resize(${w}, ${h});
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  const t = penpot.createText(${JSON.stringify(labelText)});
  t.fontFamily = 'Work Sans';
  t.fontSize = '${Math.max(11, Math.min(18, Math.round(Math.min(w, h) / 10)))}';
  t.fontWeight = '500';
  t.fills = [{ fillColor: '#555555' }];
  t.growType = 'auto-height';
  t.resize(${Math.max(120, Math.round(w * 0.7))}, 24);
  t.name = 'iframe-label';
  board.appendChild(t);
  t.x = ${x + 12};
  t.y = ${y + Math.round(h / 2) - 12};
  return { kind: 'iframe', id: r.id };
}`;
  }

  if (item.kind === 'canvas') {
    // Dark-fill rect with "canvas: WxH (webgl)" centred label. The shape name
    // doubles as the source-of-truth for canvas-to-html.mjs's classifier.
    const labelText = `canvas: ${w}x${h}${item.hasWebGL ? ' (webgl)' : ''}`;
    const nameLabel = `canvas: ${item.hasWebGL ? 'webgl ' : ''}${w}x${h}`;
    const snapJs = item.snapshot ? JSON.stringify(item.snapshot) : 'null';
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(nameLabel.slice(0, 120))};
  r.resize(${w}, ${h});
  let fillSet = false;
  const snap = ${snapJs};
  if (snap) {
    try {
      const img = await penpot.uploadMediaUrl('canvas-snapshot', snap);
      if (img) { r.fills = [{ fillOpacity: 1, fillImage: img }]; fillSet = true; }
    } catch (e) { /* swallow — WebGL snapshots often fail to round-trip */ }
  }
  if (!fillSet) {
    // Reason: the consulting starfield is near-black; a dark fill keeps the
    // placeholder honest even when we can't replay the actual pixels.
    r.fills = [{ fillColor: '#0a0a0a' }];
  }
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  const t = penpot.createText(${JSON.stringify(labelText)});
  t.fontFamily = 'Work Sans';
  t.fontSize = '${Math.max(12, Math.min(20, Math.round(Math.min(w, h) / 12)))}';
  t.fontWeight = '600';
  t.fills = [{ fillColor: '#ffffff' }];
  t.growType = 'auto-height';
  t.resize(${Math.max(140, Math.round(w * 0.5))}, 24);
  t.name = 'canvas-label';
  board.appendChild(t);
  t.x = ${x + Math.round(w / 2) - Math.max(70, Math.round(w / 4))};
  t.y = ${y + Math.round(h / 2) - 12};
  return { kind: 'canvas', id: r.id };
}`;
  }

  if (item.kind === 'lottie') {
    const fname = (item.filename || item.src || 'lottie').slice(0, 80);
    const labelText = `lottie: ${fname}`;
    const nameLabel = `lottie: ${(item.src || fname).slice(0, 100)}`;
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(nameLabel)};
  r.fills = [{ fillColor: '#f5f0fa' }];
  r.strokes = [{ strokeColor: '#9b6dc7', strokeStyle: 'dashed', strokeAlignment: 'inner', strokeWidth: 1 }];
  r.resize(${w}, ${h});
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  const t = penpot.createText(${JSON.stringify(labelText)});
  t.fontFamily = 'Work Sans';
  t.fontSize = '${Math.max(11, Math.min(16, Math.round(Math.min(w, h) / 10)))}';
  t.fontWeight = '500';
  t.fills = [{ fillColor: '#6c3a9b' }];
  t.growType = 'auto-height';
  t.resize(${Math.max(120, Math.round(w * 0.7))}, 24);
  t.name = 'lottie-label';
  board.appendChild(t);
  t.x = ${x + 12};
  t.y = ${y + Math.round(h / 2) - 12};
  return { kind: 'lottie', id: r.id };
}`;
  }

  if (item.kind === 'bg-image') {
    const label = 'bg-image: ' + ((item.src || '').slice(0, 80));
    const srcJs = JSON.stringify(item.src || '');
    return `
{
  const r = penpot.createRectangle();
  r.name = ${JSON.stringify(label)};
  r.resize(${w}, ${h});
  let fillSet = false;
  try {
    const img = await penpot.uploadMediaUrl('bg-image', ${srcJs});
    if (img) { r.fills = [{ fillOpacity: 1, fillImage: img }]; fillSet = true; }
  } catch (e) { /* swallow */ }
  if (!fillSet) {
    r.fills = [{ fillColor: '#eeeeee' }];
    r.strokes = [{ strokeColor: '#cccccc', strokeStyle: 'dashed', strokeAlignment: 'inner', strokeWidth: 1 }];
  } else {
    r.strokes = [];
  }
  board.appendChild(r);
  r.x = ${x}; r.y = ${y};
  return { kind: 'bg-image', id: r.id };
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

async function prepareBoard(sid, boardName, docWidth, docHeight, resetBoard, preservePrefix, bgColor) {
  const preserveJs = JSON.stringify(preservePrefix || '');
  // Reason: BOARD_BG is the fallback when DOM harvest failed to capture a
  // page-specific bg (transparent body+html, or undefined). Pages with a real
  // captured colour (consulting's near-black starfield) get their own fill so
  // light text reads correctly on the canvas.
  const fillColorJs = JSON.stringify(bgColor || BOARD_BG);
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
board.fills = [{ fillColor: ${fillColorJs} }];

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

async function createShapesInBatches(sid, boardId, items, batchSize) {
  const batches = chunk(items, batchSize);
  const results = [];
  for (const batch of batches) {
    // Reason: video / bg-image / canvas branches await `penpot.uploadMediaUrl`,
    // so each IIFE is async and we await its resolved value before pushing.
    // For sync branches the await is a no-op. The cssAnimation suffix " ¶anim"
    // is appended post-creation so canvas-to-html can detect motion-bearing
    // shapes by sniffing the name without needing extra storage.
    const body = batch.map(item => {
      const wantAnim = !!item.cssAnimation;
      const animTag = wantAnim ? JSON.stringify(JSON.stringify(item.cssAnimation).slice(0, 80)) : 'null';
      return `{
  const res = await (async () => ${shapeJs(item)})();
  if (res && res.id && ${wantAnim ? 'true' : 'false'}) {
    const cur = penpot.currentPage;
    const sh = cur.findShapes().find(s => s.id === res.id);
    if (sh && typeof sh.name === 'string' && !sh.name.includes(' ¶anim')) {
      sh.name = (sh.name + ' ¶anim:' + ${animTag}).slice(0, 160);
    }
  }
  out.push(res);
}`;
    }).join('\n');
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
    console.log(`  DOM: ${h.docWidth}x${h.docHeight}, ${h.items.length} items, bg=${h.pageBg || '(none)'}`);
    const breakdown = h.items.reduce((a, it) => (a[it.kind] = (a[it.kind] || 0) + 1, a), {});
    console.log(`  kinds: ${Object.entries(breakdown).map(([k, v]) => `${k}=${v}`).join(' ')}`);

    const prep = await prepareBoard(sid, page.name, h.docWidth, h.docHeight, RESET_BOARD, PRESERVE_PREFIX, h.pageBg);
    if (!prep?.result) { console.log(`  prepareBoard failed: ${JSON.stringify(prep)}`); continue; }
    const { boardId, boardX, boardY, removed, preserved, screenshot } = prep.result;
    console.log(`  board ${boardId.slice(-12)}  (anchor ${boardX},${boardY}; removed ${removed} stale children; preserved ${preserved || 0}; backdrop=${screenshot ? screenshot.name : 'none'})`);

    const batches = await createShapesInBatches(sid, boardId, h.items, BATCH_SIZE);
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
