#!/usr/bin/env node
/**
 * diagnose-live-render.mjs
 *
 * Answer one question: is the Penpot canvas showing a "live render" of the
 * portfolio (with discoverable text boxes, hyperlinks, and other interactive
 * elements) — or is it just a flat screenshot?
 *
 * For each page (home / consulting / blog), we collect three views:
 *
 *   A. CANVAS  — what Penpot has on the corresponding board.
 *                Queried via the MCP execute_code tool.
 *                Counts texts, images, hyperlinks (interactions/exports).
 *
 *   B. LIVE DOM — what the actual site contains right now.
 *                Captured via headless Playwright against portfolio_local.
 *                Counts heading levels, paragraph-like text nodes, <a href>,
 *                <input>/<textarea>, <button>, <img>, <iframe>.
 *
 *   C. IFRAME PLUGIN — what http://localhost:9005 serves.
 *                Verifies the live-preview-plugin iframe is reachable and
 *                points at portfolio_local. Notes that a cross-origin iframe
 *                is opaque to Penpot's plugin sandbox: Penpot cannot inspect
 *                its DOM, so the iframe is for the *user* to look at, not
 *                for Penpot to query.
 *
 * Verdict per page: SCREENSHOT (canvas has 0 texts/links) vs LIVE-ISH
 * (canvas has > 0 text shapes matching what the DOM says).
 *
 * Usage:
 *   node ./diagnose-live-render.mjs
 *   node ./diagnose-live-render.mjs --json    # machine-readable output
 *   node ./diagnose-live-render.mjs --page home
 *
 * Exit codes:
 *   0 — diagnostic ran successfully
 *   1 — could not reach a required service (portfolio, Penpot MCP, etc.)
 */

import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname  = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, 'portfolio-sync.config.json');

function readConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
  } catch {
    return {};
  }
}

const cfg            = readConfig();
const PORTFOLIO_URL  = cfg.portfolio_local || 'http://localhost:4321';
const MCP_URL        = 'http://localhost:4401/mcp';
const IFRAME_PLUGIN  = 'http://localhost:9005';
const ALL_PAGES      = cfg.pages || [
  { name: 'home',       path: '/' },
  { name: 'consulting', path: '/consulting' },
  { name: 'blog',       path: '/blog' },
];

const args     = process.argv.slice(2);
const JSON_OUT = args.includes('--json');
const PAGE_IDX = args.indexOf('--page');
const PAGE_PICK = PAGE_IDX !== -1 ? args[PAGE_IDX + 1] : null;
const PAGES = PAGE_PICK ? ALL_PAGES.filter(p => p.name === PAGE_PICK) : ALL_PAGES;

// ─── MCP client ──────────────────────────────────────────────────────────────

async function mcpInit() {
  const res = await fetch(MCP_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
    body: JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2024-11-05',
        capabilities: {},
        clientInfo: { name: 'diagnose-live-render', version: '1.0' },
      },
    }),
    signal: AbortSignal.timeout(10_000),
  });
  if (!res.ok) throw new Error(`MCP init ${res.status}`);
  const sid = res.headers.get('mcp-session-id') || res.headers.get('Mcp-Session-Id');
  await res.text();
  if (!sid) throw new Error('MCP init returned no session id');
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
    signal: AbortSignal.timeout(20_000),
  });
  const text = await res.text();
  const line = text.split('\n').find(l => l.startsWith('data: '));
  if (!line) throw new Error('MCP returned no data frame');
  const parsed = JSON.parse(line.slice(6));
  const payload = parsed?.result?.content?.[0]?.text;
  if (!payload) throw new Error('MCP returned empty content');
  // payload is itself a JSON-encoded { result, log } envelope
  try { return JSON.parse(payload); } catch { return { raw: payload }; }
}

// ─── A. Canvas inspection via MCP ────────────────────────────────────────────

const CANVAS_INSPECTION = `
const out = {};
for (const p of penpot.currentFile.pages) {
  const shapes = p.findShapes();
  const byType = {};
  let textShapes = 0;
  let imageShapes = 0;
  let interactiveShapes = 0;       // shapes with interactions[] entries
  let boards = 0;
  const boardChildren = {};

  for (const s of shapes) {
    byType[s.type] = (byType[s.type] || 0) + 1;
    if (s.type === 'text') textShapes++;
    // Image fills indicate a screenshot rectangle
    if (s.fills && Array.isArray(s.fills)) {
      for (const f of s.fills) {
        if (f && (f.fillImage || f.fillImageUrl || f.fillImageData)) {
          imageShapes++;
          break;
        }
      }
    }
    if (s.interactions && s.interactions.length > 0) interactiveShapes++;
    if (s.type === 'board' && s.id !== '00000000-0000-0000-0000-000000000000') {
      boards++;
      const direct = shapes.filter(c => c.parent && c.parent.id === s.id);
      boardChildren[s.name] = {
        total: direct.length,
        texts: direct.filter(c => c.type === 'text').length,
        images: direct.filter(c =>
          c.fills && c.fills.some(f => f && (f.fillImage || f.fillImageUrl || f.fillImageData))
        ).length,
      };
    }
  }

  // Collect candidate "links" via shape names that suggest hrefs
  const linkLike = shapes.filter(s => {
    if (!s.name) return false;
    const n = s.name.toLowerCase();
    return n.startsWith('http') || n.startsWith('mailto:') || n.includes('://') || n.includes('link');
  }).length;

  out[p.name] = {
    pageId: p.id,
    totalShapes: shapes.length,
    byType,
    textShapes,
    imageShapes,
    boards,
    boardChildren,
    interactiveShapes,
    linkLike,
  };
}
return out;
`;

async function inspectCanvas() {
  const sid = await mcpInit();
  return mcpExec(sid, CANVAS_INSPECTION);
}

// ─── B. Live DOM inspection via Playwright ───────────────────────────────────

async function inspectLiveDom(pages) {
  const result = {};
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });

  for (const page of pages) {
    const url = `${PORTFOLIO_URL}${page.path}`;
    const tab = await ctx.newPage();
    try {
      await tab.goto(url, { waitUntil: 'networkidle', timeout: 30_000 });
      await tab.waitForTimeout(500);
      const dom = await tab.evaluate(() => {
        const root = document.body;
        const isVisible = (el) => {
          const r = el.getBoundingClientRect();
          if (r.width === 0 || r.height === 0) return false;
          const s = window.getComputedStyle(el);
          return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity) > 0;
        };

        const texts = [];
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        let n;
        while ((n = walker.nextNode())) {
          const t = (n.textContent || '').trim();
          if (!t || t.length < 2) continue;
          if (n.parentElement && (n.parentElement.tagName === 'SCRIPT' || n.parentElement.tagName === 'STYLE')) continue;
          if (n.parentElement && !isVisible(n.parentElement)) continue;
          texts.push({
            text: t.slice(0, 80),
            tag: n.parentElement?.tagName?.toLowerCase() || '',
          });
        }

        const links = Array.from(document.querySelectorAll('a[href]'))
          .filter(isVisible)
          .map(a => ({
            href: a.getAttribute('href'),
            text: (a.textContent || '').trim().slice(0, 60),
            external: /^https?:\/\//.test(a.getAttribute('href') || ''),
          }));

        const buttons   = Array.from(document.querySelectorAll('button')).filter(isVisible).length;
        const inputs    = Array.from(document.querySelectorAll('input, textarea, select')).filter(isVisible).length;
        const images    = Array.from(document.querySelectorAll('img')).filter(isVisible).length;
        const headings  = ['h1','h2','h3','h4','h5','h6'].reduce((acc, h) => {
          acc[h] = Array.from(document.querySelectorAll(h)).filter(isVisible).length;
          return acc;
        }, {});
        const iframes   = Array.from(document.querySelectorAll('iframe')).filter(isVisible).map(f => f.getAttribute('src'));

        return {
          url:          window.location.href,
          docWidth:     document.documentElement.scrollWidth,
          docHeight:    document.documentElement.scrollHeight,
          texts:        texts.length,
          sampleTexts:  texts.slice(0, 5).map(t => t.text),
          headings,
          links:        links.length,
          externalLinks:links.filter(l => l.external).length,
          sampleLinks:  links.slice(0, 5),
          buttons,
          inputs,
          images,
          iframes,
        };
      });
      result[page.name] = dom;
    } catch (err) {
      result[page.name] = { error: err.message };
    } finally {
      await tab.close();
    }
  }
  await browser.close();
  return result;
}

// ─── C. Iframe plugin probe ──────────────────────────────────────────────────

async function inspectIframePlugin() {
  let html = null;
  try {
    const res = await fetch(IFRAME_PLUGIN, { signal: AbortSignal.timeout(4_000) });
    if (!res.ok) return { reachable: false, status: res.status };
    html = await res.text();
  } catch (e) {
    return { reachable: false, error: e.message };
  }
  const ifSrcMatch = html.match(/<iframe[^>]*src=["']([^"']+)["']/i);
  const iframeSrc = ifSrcMatch ? ifSrcMatch[1] : null;
  return {
    reachable: true,
    iframeSrc,
    pointsAtPortfolio: iframeSrc ? iframeSrc.startsWith(PORTFOLIO_URL) : false,
    crossOriginVisibilityNote:
      'Penpot plugins run in an iframe sandbox; reading the contentDocument of a cross-origin child ' +
      'iframe is blocked by the browser same-origin policy. This iframe is for the user to look at — ' +
      'it does not expose textboxes / hyperlinks to Penpot.',
  };
}

// ─── Service preflight ───────────────────────────────────────────────────────

async function preflight() {
  const probes = [
    { label: 'portfolio dev server', url: PORTFOLIO_URL },
    { label: 'Penpot MCP HTTP',     url: 'http://localhost:4401' },
    { label: 'iframe plugin',       url: IFRAME_PLUGIN, optional: true },
  ];
  const result = {};
  for (const p of probes) {
    try {
      await fetch(p.url, { signal: AbortSignal.timeout(3_000) });
      result[p.label] = 'ok';
    } catch {
      result[p.label] = p.optional ? 'down (optional)' : 'DOWN';
    }
  }
  return result;
}

// ─── Verdict logic ───────────────────────────────────────────────────────────

function verdictFor(pageName, canvas, dom) {
  if (!canvas) return { code: 'unknown', reason: `no canvas data for "${pageName}"` };
  if (dom?.error) return { code: 'dom-error', reason: dom.error };

  const board = canvas.boardChildren?.[pageName];
  const canvasTexts = board?.texts ?? canvas.textShapes ?? 0;
  const canvasImages = board?.images ?? canvas.imageShapes ?? 0;
  const domTexts = dom?.texts ?? 0;
  const domLinks = dom?.links ?? 0;

  if (canvasTexts === 0 && canvasImages > 0 && (domTexts > 0 || domLinks > 0)) {
    return {
      code: 'SCREENSHOT',
      reason: `canvas board "${pageName}" has 0 text shapes and ${canvasImages} image fill(s); ` +
              `live DOM has ${domTexts} text nodes and ${domLinks} hyperlinks. ` +
              `Penpot cannot select, edit, or click anything on this board.`,
    };
  }
  if (canvasTexts > 0 && canvasTexts < domTexts / 4) {
    return { code: 'PARTIAL', reason: `canvas has ${canvasTexts} text shapes vs ${domTexts} in DOM (< 25%).` };
  }
  if (canvasTexts > 0) {
    return { code: 'LIVE-ISH', reason: `canvas has ${canvasTexts} text shapes vs ${domTexts} in DOM.` };
  }
  return { code: 'EMPTY', reason: `no recognizable board "${pageName}" on canvas.` };
}

// ─── Report rendering ────────────────────────────────────────────────────────

function renderReport({ services, canvas, dom, iframe }) {
  const lines = [];
  lines.push('');
  lines.push('═══ portfolio↔Penpot · live-render diagnostic ═══════════════');
  lines.push('');
  lines.push('Service preflight:');
  for (const [k, v] of Object.entries(services)) {
    lines.push(`  ${v === 'ok' ? '✓' : '✗'} ${k.padEnd(28)} ${v}`);
  }

  lines.push('');
  lines.push('Iframe plugin (http://localhost:9005):');
  if (iframe.reachable) {
    lines.push(`  ✓ reachable`);
    lines.push(`  iframe src           ${iframe.iframeSrc || '(none)'}`);
    lines.push(`  points at portfolio? ${iframe.pointsAtPortfolio ? 'yes' : 'no'}`);
    lines.push(`  note → ${iframe.crossOriginVisibilityNote}`);
  } else {
    lines.push(`  ✗ not reachable (${iframe.error || iframe.status})`);
  }

  lines.push('');
  lines.push('Per-page diff (canvas vs live DOM):');
  lines.push('');

  for (const page of PAGES) {
    const c = canvas?.result?.[page.name === 'home' ? 'Page 1' : null]
           || canvas?.result?.[page.name]
           || null;
    // The portfolio "home" page lives on Penpot's "Page 1" — match on board name too
    const boardCanvas = canvas?.result;
    let pageData = null;
    if (boardCanvas) {
      for (const pname of Object.keys(boardCanvas)) {
        const bc = boardCanvas[pname].boardChildren?.[page.name];
        if (bc) { pageData = { pageName: pname, ...boardCanvas[pname] }; break; }
      }
      if (!pageData) pageData = c;
    }

    const d = dom[page.name];
    const v = verdictFor(page.name, pageData, d);

    lines.push(`  ── ${page.name}  (${PORTFOLIO_URL}${page.path}) ──`);
    if (pageData) {
      const bc = pageData.boardChildren?.[page.name];
      lines.push(`     CANVAS  page="${pageData.pageName || '?'}"`);
      lines.push(`             board "${page.name}": ${bc?.total ?? '?'} children ` +
                 `(texts=${bc?.texts ?? 0}, images=${bc?.images ?? 0})`);
      lines.push(`             page totals: text shapes=${pageData.textShapes}, ` +
                 `image-fill shapes=${pageData.imageShapes}, ` +
                 `link-like=${pageData.linkLike}, ` +
                 `interactive=${pageData.interactiveShapes}`);
    } else {
      lines.push(`     CANVAS  no board named "${page.name}" found on any page`);
    }

    if (d.error) {
      lines.push(`     DOM     error: ${d.error}`);
    } else {
      lines.push(`     DOM     ${d.docWidth}×${d.docHeight}, texts=${d.texts}, ` +
                 `links=${d.links} (external=${d.externalLinks}), ` +
                 `buttons=${d.buttons}, inputs=${d.inputs}, images=${d.images}, ` +
                 `iframes=${d.iframes.length}`);
      const heads = Object.entries(d.headings).filter(([,n]) => n > 0).map(([h,n]) => `${h}=${n}`).join(' ');
      if (heads) lines.push(`             headings: ${heads}`);
      if (d.sampleTexts.length) lines.push(`             sample text: ${JSON.stringify(d.sampleTexts[0])}`);
      if (d.sampleLinks.length) lines.push(`             sample link: ${JSON.stringify(d.sampleLinks[0])}`);
    }

    lines.push(`     VERDICT ${v.code}  — ${v.reason}`);
    lines.push('');
  }

  lines.push('Reading the verdict:');
  lines.push('  SCREENSHOT  canvas board shows an image; DOM is rich; Penpot can\'t introspect it.');
  lines.push('  PARTIAL     canvas has some text shapes but most of the DOM is missing.');
  lines.push('  LIVE-ISH    canvas text shape count is on the order of DOM text node count.');
  lines.push('');
  lines.push('Iframe plugin caveat:');
  lines.push('  the live-preview iframe at :9005 is for the human looking at Penpot —');
  lines.push('  Penpot cannot read its DOM because the iframe is cross-origin.');
  lines.push('  If you need Penpot to *see* text boxes and hyperlinks, the right path is to');
  lines.push('  generate createText / createRectangle shapes from the DOM (Task 2 was the');
  lines.push('  start of that — see tools/portfolio-sync/run_task2 equivalent in ./scripts).');
  lines.push('═══════════════════════════════════════════════════════════════');
  lines.push('');
  return lines.join('\n');
}

// ─── Main ────────────────────────────────────────────────────────────────────

async function main() {
  const services = await preflight();

  // Hard requirements: portfolio + MCP
  const hardFail = Object.entries(services)
    .filter(([k, v]) => v === 'DOWN')
    .map(([k]) => k);
  if (hardFail.length) {
    console.error(`Cannot run diagnostic — required services down: ${hardFail.join(', ')}`);
    console.error(JSON.stringify(services, null, 2));
    process.exit(1);
  }

  const [canvas, dom, iframe] = await Promise.all([
    inspectCanvas().catch(err => ({ error: err.message })),
    inspectLiveDom(PAGES).catch(err => ({ error: err.message })),
    inspectIframePlugin(),
  ]);

  const payload = { services, canvas, dom, iframe, pages: PAGES.map(p => p.name) };

  if (JSON_OUT) {
    console.log(JSON.stringify(payload, null, 2));
    return;
  }

  console.log(renderReport(payload));
}

main().catch(err => {
  console.error('diagnose-live-render error:', err.message);
  process.exit(1);
});
