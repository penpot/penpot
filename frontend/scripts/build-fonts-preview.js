// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// Copyright (c) KALEIDOS INC Sucursal en España SL

// Builds one SVG sprite previewing every catalog (built-in + Google) font name,
// outlined in its own typeface, so the picker loads it once instead of one
// request per font. Custom uploads aren't baked in and use the runtime fallback.
//
// Part of the asset build (compileFontsPreviewSprite in _helpers.js):
// regenerates only when missing or older than the gfonts catalog. Run directly
// to force a rebuild. See the technical-guide doc for the full design.

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import ph from "node:path";
import url from "node:url";

import opentype from "opentype.js";

// Coordinates are emitted in CSS px (1 user unit == 1px), so the UI sizes rows
// without per-font metrics. Each name is laid out on its baseline then fitted
// into a fixed BOX_HEIGHT box (see buildSymbol). See the technical-guide doc.
const FONT_SIZE = 18; // visual cap/x-height target, matches the label typography
const BOX_HEIGHT = 28; // display box height in px; keep in sync with the scss
const VPAD = 1; // px of breathing room top/bottom before a name is scaled to fit
// Decimals of precision. 1 (≈0.1px grid) keeps glyphs smooth; 0 makes them
// wobbly. Sprite is ~2.3MB gzip at 1 with the relative encoding (serializePath).
const PATH_PRECISION = 1;

const CACHE_DIR = ph.join(os.tmpdir(), "penpot-fonts-preview-cache");
const CONCURRENCY = 24;
// Written straight to the served dir (like the SVG sprite), and excluded from
// copyAssets' `rsync --delete` so it survives builds — see the doc / _helpers.js.
export const OUTPUT = "resources/public/fonts/fonts-preview-sprite.svg";

// Color fonts (COLR/SVG glyph tables) make opentype.js take a browser-only
// DOMParser path that rejects a promise and crashes Node. We only need vector
// outlines, so swallow those rejections and the COLR warning during generation
// (restored afterwards so other build steps are unaffected).
function installFontParsingGuards() {
  const onRejection = () => {};
  process.on("unhandledRejection", onRejection);

  const origWarn = console.warn;
  console.warn = (msg, ...rest) => {
    if (typeof msg === "string" && msg.includes("COLR")) return;
    origWarn(msg, ...rest);
  };

  return () => {
    process.off("unhandledRejection", onRejection);
    console.warn = origWarn;
  };
}

// Mirror of cuerdas.core/slug as used by the `parse-gfont` macro in
// src/app/main/fonts.clj so that ids match `(str "gfont-" (str/slug family))`.
function slug(value) {
  return value
    .normalize("NFKD")
    .replace(/[̀-ͯ]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

async function findGfontsJson() {
  const dir = "resources/fonts";
  const entries = await fs.readdir(dir);
  const matches = entries.filter((f) => /^gfonts\..*\.json$/.test(f)).sort();
  if (matches.length === 0) {
    throw new Error(`no gfonts.*.json found in ${dir}`);
  }
  return ph.join(dir, matches[matches.length - 1]);
}

async function readCatalog() {
  const builtin = [
    {
      id: "sourcesanspro",
      name: "Source Sans Pro",
      source: {
        type: "file",
        path: "resources/fonts/sourcesanspro-regular.ttf",
      },
    },
  ];

  const gfontsPath = await findGfontsJson();
  const raw = JSON.parse(await fs.readFile(gfontsPath, "utf-8"));
  const google = (raw.items || []).map((item) => {
    const family = item.family;
    // Prefer the "menu" subset: a tiny TTF Google ships containing exactly the
    // glyphs needed to render the family name in a font picker.
    const url =
      item.menu ||
      (item.files && (item.files.regular || Object.values(item.files)[0]));
    return {
      id: `gfont-${slug(family)}`,
      name: family,
      source: { type: "url", url },
    };
  });

  return [...builtin, ...google];
}

async function fetchWithCache(url) {
  const key = createHash("sha1").update(url).digest("hex") + ".ttf";
  const cached = ph.join(CACHE_DIR, key);
  try {
    return await fs.readFile(cached);
  } catch {
    // not cached yet
  }

  let lastErr;
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const buf = Buffer.from(await res.arrayBuffer());
      await fs.writeFile(cached, buf);
      return buf;
    } catch (err) {
      lastErr = err;
    }
  }
  throw lastErr;
}

async function loadFontBuffer(source) {
  if (source.type === "file") return fs.readFile(source.path);
  if (!source.url) throw new Error("missing font url");
  return fetchWithCache(source.url);
}

// Lay the name out glyph-by-glyph with sanitized advances, NOT via
// `font.getPath`: some menu subsets have a broken kern/NaN advance that emits
// `NaN` into the path, which makes browsers stop parsing mid-path (only the
// first glyph renders). Baseline at y=0; buildSymbol re-centers afterwards.
// `hasTofu` flags glyphs the subset lacks (.notdef → "tofu" boxes), dropping the
// font to the runtime fallback.
function renderName(font, text) {
  const scale = FONT_SIZE / font.unitsPerEm;
  const path = new opentype.Path();
  let x = 0;
  let hasTofu = false;
  for (const ch of text) {
    const glyph = font.charToGlyph(ch);
    if (glyph.index === 0 && ch.trim() !== "") hasTofu = true;
    path.extend(glyph.getPath(x, 0, FONT_SIZE));
    const advance = glyph.advanceWidth;
    x += (Number.isFinite(advance) ? advance : font.unitsPerEm * 0.5) * scale;
  }
  return { path, hasTofu };
}

// Round to PATH_PRECISION decimals (normalizing -0). Done BEFORE deltas are
// taken (serializePath) so the relative encoding never drifts.
function roundGrid(value) {
  return Number(value.toFixed(PATH_PRECISION)) + 0;
}

// Format an (already-rounded) number compactly: drop the integer "0" from
// "0.x"/"-0.x" to save bytes (".x"/"-.x" are valid SVG numbers).
function fmt(n) {
  let s = n.toString();
  if (s.startsWith("0.")) s = s.slice(1);
  else if (s.startsWith("-0.")) s = "-" + s.slice(2);
  return s;
}

// Serialize paths ourselves (opentype's `toPathData` separates numbers with
// spaces only — ambiguous enough that browsers bail mid-path). Format: comma
// WITHIN a pair, space BETWEEN pairs. Commands are RELATIVE (lowercase m/l/q/c)
// for much smaller, better-compressing output (~6.2MB → ~2.3MB gzip), with no
// geometry change. Curve control points are relative to the pen before the
// command, and `z` returns the pen to the subpath start — both tracked below.
// Coordinates are baked through the affine fit (scale `s`, translate tx/ty).
function serializePath(commands, s, tx, ty) {
  const ax = (v) => roundGrid(v * s + tx);
  const ay = (v) => roundGrid(v * s + ty);
  let out = "";
  // px/py: current pen (rounded, absolute). sx/sy: start of the current subpath,
  // which the pen snaps back to on `z`.
  let px = 0,
    py = 0,
    sx = 0,
    sy = 0;
  // Re-round the delta: subtracting two grid values reintroduces float noise
  // (13.5 - 10.9 === 2.6000000000000014). Drift-free, since the pen tracks the
  // exact rounded absolute (x/y), not the emitted delta.
  const d = (to, from) => fmt(roundGrid(to - from));
  for (const c of commands) {
    switch (c.type) {
      case "M": {
        const x = ax(c.x),
          y = ay(c.y);
        out += `m${d(x, px)},${d(y, py)}`;
        px = sx = x;
        py = sy = y;
        break;
      }
      case "L": {
        const x = ax(c.x),
          y = ay(c.y);
        out += `l${d(x, px)},${d(y, py)}`;
        px = x;
        py = y;
        break;
      }
      case "Q": {
        const x1 = ax(c.x1),
          y1 = ay(c.y1),
          x = ax(c.x),
          y = ay(c.y);
        out += `q${d(x1, px)},${d(y1, py)} ${d(x, px)},${d(y, py)}`;
        px = x;
        py = y;
        break;
      }
      case "C": {
        const x1 = ax(c.x1),
          y1 = ay(c.y1),
          x2 = ax(c.x2),
          y2 = ay(c.y2),
          x = ax(c.x),
          y = ay(c.y);
        out +=
          `c${d(x1, px)},${d(y1, py)} ` +
          `${d(x2, px)},${d(y2, py)} ${d(x, px)},${d(y, py)}`;
        px = x;
        py = y;
        break;
      }
      case "Z":
        out += "z";
        px = sx;
        py = sy;
        break;
    }
  }
  return out;
}

function buildSymbol({ id, name }, buffer) {
  const ab = buffer.buffer.slice(
    buffer.byteOffset,
    buffer.byteOffset + buffer.byteLength,
  );
  const font = opentype.parse(ab);
  // Drop the SVG color-glyph table to force outline rendering (see above).
  font.tables.svg = undefined;

  const { path, hasTofu } = renderName(font, name);
  // Drop fonts whose name needs glyphs the subset lacks — they would render as
  // .notdef boxes; the runtime fallback loads the real font instead.
  if (hasTofu) throw new Error("missing glyphs (tofu)");

  const bb = path.getBoundingBox();
  if (!Number.isFinite(bb.x1) || bb.x2 <= bb.x1) throw new Error("empty path");

  // Fit into BOX_HEIGHT, centered by bounding box so tall ascenders/descenders
  // aren't clipped; names taller than the box are scaled down. Left-aligned at 0.
  const usable = BOX_HEIGHT - 2 * VPAD;
  const height = bb.y2 - bb.y1;
  const s = height > usable ? usable / height : 1;
  const tx = -bb.x1 * s;
  const ty = BOX_HEIGHT / 2 - ((bb.y1 + bb.y2) / 2) * s;

  const d = serializePath(path.commands, s, tx, ty);
  if (!d || d.length === 0) throw new Error("empty path");
  // A stray NaN (non-finite outline point) would truncate the glyph in the
  // browser; drop the font so it cleanly falls back to the runtime loader.
  if (d.includes("NaN")) throw new Error("non-finite path");
  // No `fill`: the UI's <use> provides `currentColor` so it follows the theme.
  return `<g id="font-preview-${id}"><path d="${d}"/></g>`;
}

async function mapLimit(items, limit, fn) {
  const results = new Array(items.length);
  let cursor = 0;
  async function worker() {
    while (cursor < items.length) {
      const index = cursor++;
      results[index] = await fn(items[index], index);
    }
  }
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, worker),
  );
  return results;
}

// True when the sprite exists and is at least as new as the gfonts catalog, so
// the build can skip the expensive, network-bound regeneration.
export async function isSpriteUpToDate(outputPath = OUTPUT) {
  try {
    const gfontsPath = await findGfontsJson();
    const [outStat, gfontsStat] = await Promise.all([
      fs.stat(outputPath),
      fs.stat(gfontsPath),
    ]);
    return outStat.mtimeMs >= gfontsStat.mtimeMs;
  } catch {
    // output missing (or no catalog) → not up to date
    return false;
  }
}

// Regenerate the sprite into `outputPath`. Skips (returns `{ skipped: true }`)
// when the output is already up to date, unless `force` is set. On a real
// rebuild it returns `{ skipped:false, ok, failed, bytes }`.
export async function buildFontsPreviewSprite({
  outputPath = OUTPUT,
  force = false,
} = {}) {
  if (!force && (await isSpriteUpToDate(outputPath))) {
    return { skipped: true };
  }

  const restoreGuards = installFontParsingGuards();
  try {
    await fs.mkdir(CACHE_DIR, { recursive: true });

    const catalog = await readCatalog();
    console.log(`building font preview sprite for ${catalog.length} fonts…`);

    let ok = 0;
    const failed = [];
    const symbols = await mapLimit(catalog, CONCURRENCY, async (font) => {
      try {
        const buffer = await loadFontBuffer(font.source);
        const symbol = buildSymbol(font, buffer);
        ok++;
        if (ok % 200 === 0) console.log(`  …${ok}/${catalog.length}`);
        return symbol;
      } catch (err) {
        failed.push({ id: font.id, reason: err.message });
        return null;
      }
    });

    const body = symbols.filter(Boolean).join("");
    const sprite =
      `<svg xmlns="http://www.w3.org/2000/svg" style="display:none" aria-hidden="true">` +
      body +
      `</svg>\n`;

    await fs.mkdir(ph.dirname(outputPath), { recursive: true });
    await fs.writeFile(outputPath, sprite);

    if (failed.length) {
      console.log(
        `font preview sprite: ${failed.length} fonts will use the runtime fallback:`,
      );
      for (const f of failed.slice(0, 40))
        console.log(`  - ${f.id}: ${f.reason}`);
      if (failed.length > 40) console.log(`  …and ${failed.length - 40} more`);
    }

    return { skipped: false, ok, failed: failed.length, bytes: sprite.length };
  } finally {
    restoreGuards();
  }
}

// When invoked directly (`node scripts/build-fonts-preview.js`), force a full
// rebuild regardless of the up-to-date check.
const isDirectRun =
  process.argv[1] &&
  import.meta.url === url.pathToFileURL(process.argv[1]).href;

if (isDirectRun) {
  const res = await buildFontsPreviewSprite({ force: true });
  const mb = (res.bytes / 1024 / 1024).toFixed(1);
  console.log(
    `done: ${res.ok} ok, ${res.failed} failed → ${OUTPUT} (${mb} MB, served gzipped)`,
  );
}
