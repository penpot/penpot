#!/usr/bin/env node
/**
 * install-font.mjs
 *
 * Install a Google-Fonts font family into the local Penpot instance so that
 * `penpot.fonts.all` includes it on the next session.  Standalone — no npm
 * deps, no project wiring.  Run after a `build-live-dom-canvas` run prints
 * a "→ on Google Fonts: install with…" hint.
 *
 * Pipeline:
 *
 *   1. Read portfolio-sync.config.json (penpot_url + penpot_email + penpot_password).
 *   2. POST /api/rpc/command/login-with-password — keep the auth-token cookie.
 *   3. POST /api/rpc/command/get-profile — pull default-team-id.
 *   4. POST /api/rpc/command/get-font-variants — short-circuit if family exists.
 *   5. GET https://fonts.googleapis.com/css2?family=<name> with a bare UA
 *      so Google serves us TTF (not woff2 — Penpot 2.15's woff2 path drops
 *      derived TTF before persisting; see comment on SIMPLE_UA).  Parse
 *      `@font-face` blocks, pick a representative weight×style set, download
 *      each TTF.
 *   6. Per variant:  POST /api/rpc/command/create-upload-session  →
 *      POST /api/rpc/command/upload-chunk (multipart, single chunk)  →
 *      POST /api/rpc/command/create-font-variant  with the session-id.
 *   7. Re-query /api/rpc/command/get-font-variants and confirm the family
 *      now appears.
 *
 * CLI:
 *   node install-font.mjs "Family Name"
 *   node install-font.mjs --list-installed
 *   node install-font.mjs --help
 *
 * Exit codes:
 *   0  – success (installed, or already installed)
 *   1  – usage error / unhandled exception
 *   2  – family not on Google Fonts
 *   3  – partial failure during upload (one or more variants failed)
 *   4  – missing config keys (penpot_email / penpot_password)
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname   = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_CONFIG_PATH = path.join(__dirname, 'portfolio-sync.config.json');
// Allow overriding via env var for ad-hoc runs without editing the committed
// config (useful for smoke-testing with throwaway creds).
const CONFIG_PATH = process.env.PORTFOLIO_SYNC_CONFIG || DEFAULT_CONFIG_PATH;

// Google Fonts serves different formats based on User-Agent:
//   - Modern Chrome UA → woff2 with per-subset slicing
//   - Bare "Mozilla/5.0" → single TTF per variant, no subsetting
// We deliberately use the bare UA because Penpot 2.15's create-font-variant
// pipeline mis-handles woff2-only uploads (woff2_decompress runs successfully
// in the container but the generated TTF is dropped before persist).  Sending
// TTF up-front avoids that entire code path — Penpot generates otf+woff from
// the TTF via fontforge + sfnt2woff which we've verified works.
const SIMPLE_UA = 'Mozilla/5.0';
// Real Chrome UA (kept for fallback / future use if the woff2 path is fixed).
const CHROME_UA =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';

// Penpot's local MCP bridge — used purely to ask `penpot.fonts.all` whether
// the family is already known (covers the ~1900 bundled Google Fonts).
const MCP_URL = 'http://localhost:4401/mcp';

const args = process.argv.slice(2);

function printHelp() {
  process.stdout.write(`install-font.mjs — push a Google-Fonts family into local Penpot

Usage:
  node install-font.mjs "Family Name"     install one family
  node install-font.mjs --list-installed  list families currently in Penpot
  node install-font.mjs --help            show this message

Config:
  Reads portfolio-sync.config.json from the same directory.  Requires:
    penpot_url       (default: http://localhost:9001)
    penpot_email
    penpot_password

Notes:
  - The family is matched case-insensitively against Penpot's existing custom
    fonts; if it's already there, the script exits 0 with "already installed".
  - For variable fonts, a representative 400/700 normal + 400 italic subset is
    installed, not every weight Google offers.
`);
}

// ─── config ────────────────────────────────────────────────────────────────

function readConfigOrDie() {
  let raw;
  try { raw = fs.readFileSync(CONFIG_PATH, 'utf8'); }
  catch (e) {
    fail(`could not read ${CONFIG_PATH}: ${e.message}`, 1);
  }
  let parsed;
  try { parsed = JSON.parse(raw); }
  catch (e) { fail(`${CONFIG_PATH} is not valid JSON: ${e.message}`, 1); }

  const missing = [];
  if (!parsed.penpot_email)    missing.push('penpot_email');
  if (!parsed.penpot_password) missing.push('penpot_password');

  if (missing.length > 0) {
    const blob = `
ERROR: portfolio-sync.config.json is missing required key(s): ${missing.join(', ')}

Add them to ${CONFIG_PATH}, for example:

  {
    ...,
    "penpot_url": "http://localhost:9001",
    "penpot_email": "you@example.com",
    "penpot_password": "your-penpot-password"
  }

(Your local Penpot creds — these never leave this machine.)
`;
    process.stderr.write(blob);
    process.exit(4);
  }

  return {
    penpotUrl:   parsed.penpot_url   || 'http://localhost:9001',
    email:       parsed.penpot_email,
    password:    parsed.penpot_password,
  };
}

function fail(msg, code = 1) {
  process.stderr.write(`install-font: ${msg}\n`);
  process.exit(code);
}

// ─── Penpot RPC client ─────────────────────────────────────────────────────

class PenpotClient {
  constructor(baseUrl) {
    this.baseUrl = baseUrl.replace(/\/$/, '');
    this.cookie  = '';  // raw Cookie: header value, populated by login()
  }

  // Plain JSON RPC call.  Returns parsed JSON (or null for 204).
  async rpc(method, body = {}) {
    const url = `${this.baseUrl}/api/rpc/command/${method}`;
    const res = await fetch(url, {
      method:  'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept':       'application/json',
        ...(this.cookie ? { 'Cookie': this.cookie } : {}),
      },
      body: JSON.stringify(body),
    });
    await this._captureCookies(res);

    if (res.status === 204) return null;
    const text = await res.text();
    let json;
    try { json = text ? JSON.parse(text) : null; } catch {
      throw new Error(`rpc ${method}: non-JSON response (status ${res.status}): ${text.slice(0, 200)}`);
    }
    if (!res.ok) {
      const code = json?.code || json?.type || 'unknown';
      const hint = json?.hint || json?.message || text.slice(0, 200);
      throw new Error(`rpc ${method} failed (${res.status} ${code}): ${hint}`);
    }
    return json;
  }

  // Multipart upload (used only by :upload-chunk).
  async rpcMultipart(method, fields) {
    const url = `${this.baseUrl}/api/rpc/command/${method}`;
    const { body, contentType } = buildMultipartBody(fields);
    const res = await fetch(url, {
      method:  'POST',
      headers: {
        'Content-Type': contentType,
        'Accept':       'application/json',
        ...(this.cookie ? { 'Cookie': this.cookie } : {}),
      },
      body,
    });
    await this._captureCookies(res);

    if (res.status === 204) return null;
    const text = await res.text();
    let json;
    try { json = text ? JSON.parse(text) : null; } catch {
      throw new Error(`rpc ${method}: non-JSON multipart response (status ${res.status}): ${text.slice(0, 200)}`);
    }
    if (!res.ok) {
      const code = json?.code || json?.type || 'unknown';
      const hint = json?.hint || json?.message || text.slice(0, 200);
      throw new Error(`rpc ${method} failed (${res.status} ${code}): ${hint}`);
    }
    return json;
  }

  async _captureCookies(res) {
    // Node fetch exposes set-cookie via res.headers.getSetCookie() (Node 19.7+).
    let setCookies = [];
    if (typeof res.headers.getSetCookie === 'function') {
      setCookies = res.headers.getSetCookie();
    } else {
      const raw = res.headers.get('set-cookie');
      if (raw) setCookies = [raw];
    }
    if (setCookies.length === 0) return;

    const jar = Object.fromEntries(
      this.cookie ? this.cookie.split('; ').filter(Boolean).map(p => {
        const i = p.indexOf('=');
        return [p.slice(0, i), p.slice(i + 1)];
      }) : []
    );
    for (const raw of setCookies) {
      const [pair] = raw.split(';');
      const i = pair.indexOf('=');
      if (i === -1) continue;
      jar[pair.slice(0, i).trim()] = pair.slice(i + 1).trim();
    }
    this.cookie = Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ');
  }

  async login(email, password) {
    await this.rpc('login-with-password', { email, password });
    if (!this.cookie.includes('auth-token=')) {
      throw new Error('login succeeded but no auth-token cookie was set');
    }
  }
}

// Build a multipart/form-data body the way Penpot's wrap-params expects.
// Returns { body: Buffer, contentType: string }.
function buildMultipartBody(fields) {
  const boundary = '----PenpotInstallFont' + crypto.randomBytes(12).toString('hex');
  const CRLF = '\r\n';
  const parts = [];

  for (const [name, value] of Object.entries(fields)) {
    parts.push(Buffer.from(`--${boundary}${CRLF}`, 'utf8'));
    if (value && typeof value === 'object' && value.filename != null) {
      // file part
      const mtype = value.mtype || 'application/octet-stream';
      const filename = value.filename;
      parts.push(Buffer.from(
        `Content-Disposition: form-data; name="${name}"; filename="${filename}"${CRLF}` +
        `Content-Type: ${mtype}${CRLF}${CRLF}`,
        'utf8'
      ));
      parts.push(value.data);
      parts.push(Buffer.from(CRLF, 'utf8'));
    } else {
      // text part
      parts.push(Buffer.from(
        `Content-Disposition: form-data; name="${name}"${CRLF}${CRLF}` +
        String(value) + CRLF,
        'utf8'
      ));
    }
  }
  parts.push(Buffer.from(`--${boundary}--${CRLF}`, 'utf8'));
  return {
    body: Buffer.concat(parts),
    contentType: `multipart/form-data; boundary=${boundary}`,
  };
}

// ─── Google Fonts CSS parsing ─────────────────────────────────────────────

async function fetchGoogleFontsCss(family) {
  // Bare UA → Google returns TTF + one block per variant.  Ask for a
  // representative weight × style matrix in one round-trip.
  const spec = `${encodeURIComponent(family)}:ital,wght@0,300;0,400;0,500;0,700;0,900;1,400;1,700`;
  const url  = `https://fonts.googleapis.com/css2?family=${spec}&display=swap`;
  const res  = await fetch(url, {
    headers: { 'User-Agent': SIMPLE_UA, 'Accept': 'text/css,*/*;q=0.1' },
    signal: AbortSignal.timeout(15_000),
  });
  if (res.status === 400 || res.status === 404) {
    // Fall back to the bare family form — some single-weight fonts (e.g.
    // display fonts with no italic) 400 the ital,wght spec but accept the
    // plain family.
    const r2 = await fetch(
      `https://fonts.googleapis.com/css2?family=${encodeURIComponent(family)}&display=swap`,
      { headers: { 'User-Agent': SIMPLE_UA, 'Accept': 'text/css,*/*;q=0.1' },
        signal: AbortSignal.timeout(15_000) }
    );
    if (!r2.ok) return null;
    return await r2.text();
  }
  if (!res.ok) return null;
  return await res.text();
}

// Parse @font-face blocks → [{ family, style, weight, url, unicodeRange }].
function parseFontFaceBlocks(css) {
  const blocks = [];
  // Iterate every @font-face block.
  const blockRe = /@font-face\s*\{([\s\S]*?)\}/g;
  let m;
  while ((m = blockRe.exec(css)) !== null) {
    const body = m[1];
    const get  = (key) => {
      const re = new RegExp(`${key}\\s*:\\s*([^;]+);`, 'i');
      const mm = re.exec(body);
      return mm ? mm[1].trim() : null;
    };
    const family = (get('font-family') || '').replace(/^['"]|['"]$/g, '');
    const style  = (get('font-style')  || 'normal').toLowerCase();
    const weight = get('font-weight') || '400';
    const srcRaw = get('src') || '';
    const urlMatch = /url\(\s*([^)\s]+?)\s*\)\s*format\(\s*['"]?([^'")]+)['"]?\s*\)/i.exec(srcRaw);
    if (!urlMatch) continue;
    const url    = urlMatch[1].replace(/^['"]|['"]$/g, '');
    const format = urlMatch[2].toLowerCase();
    const unicodeRange = get('unicode-range') || '';
    blocks.push({ family, style, weight, url, format, unicodeRange });
  }
  return blocks;
}

// Pick the latin subset for each (style, weight) combo and dedupe.
// Returns an array of { family, style, weight, url } ready to download.
function pickRepresentativeVariants(blocks) {
  if (blocks.length === 0) return [];

  // Group by (style, weight) — pick one URL per group, preferring latin.
  // The Google Fonts CSS emits per-subset blocks; the *last* one with no
  // unicode-range comment marker tagged as `/* latin */` is what we want.
  const groups = new Map();
  for (const b of blocks) {
    const key = `${b.style}|${b.weight}`;
    const existing = groups.get(key);

    // Heuristic: the "latin" block has a unicodeRange that includes U+0000-00FF.
    const isLatin = /U\+0000-00FF/i.test(b.unicodeRange) || b.unicodeRange === '';
    if (!existing) {
      groups.set(key, { ...b, _isLatin: isLatin });
    } else if (isLatin && !existing._isLatin) {
      groups.set(key, { ...b, _isLatin: isLatin });
    }
  }

  const out = [];
  for (const v of groups.values()) {
    out.push({
      family: v.family,
      style:  v.style,
      weight: normaliseWeight(v.weight),
      url:    v.url,
      format: v.format,
    });
  }
  return out;
}

// Penpot accepts 100..900 + 950.  Google sometimes emits ranges like
// "100 900" (variable fonts) — pick 400 in that case.
function normaliseWeight(w) {
  const trimmed = String(w).trim();
  if (/^\d+\s+\d+$/.test(trimmed)) {
    // Variable-font weight range — Penpot stores discrete variants, so
    // we install 400 only (the caller may invoke per-weight later).
    return 400;
  }
  const n = parseInt(trimmed, 10);
  if (Number.isNaN(n)) return 400;
  return n;
}

// ─── MCP probe of penpot.fonts.all (best-effort) ──────────────────────────

// Returns Set<lowercase family name> of every font currently visible to the
// active Penpot plugin session, or null if MCP is unreachable (caller should
// fall back to get-font-variants which only covers custom team fonts).
async function probeBundledFontsViaMcp() {
  try {
    const init = await fetch(MCP_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' },
      body: JSON.stringify({
        jsonrpc: '2.0', id: 1, method: 'initialize',
        params: { protocolVersion: '2024-11-05', capabilities: {},
                  clientInfo: { name: 'install-font', version: '1.0' } },
      }),
      signal: AbortSignal.timeout(5_000),
    });
    if (!init.ok) return null;
    const sid = init.headers.get('mcp-session-id') || init.headers.get('Mcp-Session-Id');
    await init.text();
    if (!sid) return null;

    const exec = await fetch(MCP_URL, {
      method: 'POST',
      headers: {
        'Content-Type':   'application/json',
        'Accept':         'application/json, text/event-stream',
        'mcp-session-id': sid,
      },
      body: JSON.stringify({
        jsonrpc: '2.0', id: 2, method: 'tools/call',
        params: { name: 'execute_code', arguments: { code: 'return penpot.fonts.all.map(f => f.name);' } },
      }),
      signal: AbortSignal.timeout(15_000),
    });
    const text = await exec.text();
    const line = text.split('\n').find(l => l.startsWith('data: '));
    if (!line) return null;
    const parsed = JSON.parse(line.slice(6));
    const payload = parsed?.result?.content?.[0]?.text;
    if (!payload || /^Tool execution failed/.test(payload)) return null;
    const names = JSON.parse(payload);
    if (!Array.isArray(names)) return null;
    return new Set(names.map(n => String(n).toLowerCase()));
  } catch {
    return null;
  }
}

async function downloadFontFile(url) {
  // Use the bare UA here too — gstatic.com itself doesn't care, but stays
  // consistent with the format negotiation done at the CSS step.
  const res = await fetch(url, {
    headers: { 'User-Agent': SIMPLE_UA },
    signal: AbortSignal.timeout(30_000),
  });
  if (!res.ok) throw new Error(`download ${url} failed: ${res.status}`);
  const ab = await res.arrayBuffer();
  return Buffer.from(ab);
}

// ─── Install one variant ──────────────────────────────────────────────────

async function installVariant(client, teamId, fontId, variant, buffer, mtype) {
  // 1. Open a single-chunk upload session.
  const session = await client.rpc('create-upload-session', { 'total-chunks': 1 });
  const sessionId = session['session-id'] || session.sessionId;
  if (!sessionId) throw new Error(`create-upload-session returned no session-id: ${JSON.stringify(session)}`);

  // 2. Upload the whole font as a single chunk.
  await client.rpcMultipart('upload-chunk', {
    'session-id': sessionId,
    'index':      '0',
    'content':    {
      filename: `${variant.family}-${variant.weight}-${variant.style}.${mtype === 'font/woff2' ? 'woff2' : 'ttf'}`,
      mtype,
      data: buffer,
    },
  });

  // 3. Materialise the font-variant.  `uploads` maps mtype → session-id.
  return await client.rpc('create-font-variant', {
    'team-id':      teamId,
    'font-id':      fontId,
    'font-family':  variant.family,
    'font-weight':  variant.weight,
    'font-style':   variant.style,
    'uploads':      { [mtype]: sessionId },
  });
}

// ─── Top-level orchestrator ───────────────────────────────────────────────

async function installFamily(family) {
  const { penpotUrl, email, password } = readConfigOrDie();
  const client = new PenpotClient(penpotUrl);

  process.stdout.write(`▸ Login as ${email}\n`);
  await client.login(email, password);

  process.stdout.write(`▸ Fetch profile (default team id)\n`);
  const profile = await client.rpc('get-profile', {});
  const teamId  = profile['default-team-id'] || profile.defaultTeamId;
  if (!teamId) throw new Error('profile has no default-team-id — is the account fully provisioned?');

  process.stdout.write(`▸ Fetch installed font variants for team ${teamId}\n`);
  const variants = await client.rpc('get-font-variants', { 'team-id': teamId });
  // Penpot returns camelCase keys when Accept: application/json is set, but
  // older snake/kebab callers also work — tolerate both shapes.
  const familyOf = v => (v['font-family'] || v.fontFamily || '').trim();
  const installed = new Map();
  for (const v of (variants || [])) {
    const fam = familyOf(v);
    if (!fam) continue;
    if (!installed.has(fam.toLowerCase())) installed.set(fam.toLowerCase(), fam);
  }

  if (installed.has(family.toLowerCase())) {
    process.stdout.write(`✓ "${installed.get(family.toLowerCase())}" already installed as a custom team font (${variants.length} total variants) — nothing to do.\n`);
    return 0;
  }

  // Also check Penpot's bundled-font registry via MCP — covers the ~1900
  // Google Fonts that ship with Penpot.  If unreachable we just skip this
  // check (the user will only "pay" by reinstalling as a duplicate custom
  // variant, which is harmless).
  process.stdout.write(`▸ Probe penpot.fonts.all via MCP\n`);
  const bundled = await probeBundledFontsViaMcp();
  if (bundled === null) {
    process.stdout.write(`  (MCP unreachable — skipping bundled-font precheck; open http://localhost:9001/auto-login.html to enable it)\n`);
  } else if (bundled.has(family.toLowerCase())) {
    process.stdout.write(`✓ "${family}" already ships with Penpot (${bundled.size} bundled families in penpot.fonts.all) — nothing to do.\n`);
    return 0;
  } else {
    process.stdout.write(`  not in bundled registry (${bundled.size} families) — will install as custom team font\n`);
  }

  process.stdout.write(`▸ Fetch Google Fonts CSS for "${family}"\n`);
  const css = await fetchGoogleFontsCss(family);
  if (!css) {
    process.stderr.write(`✗ "${family}" not found on Google Fonts.  Falling back to your existing fonts.\n`);
    const sample = [...installed.values()].slice(0, 6).join(', ');
    if (sample) process.stderr.write(`  Installed families you can use instead: ${sample}…\n`);
    return 2;
  }
  const blocks = parseFontFaceBlocks(css);
  const wanted = pickRepresentativeVariants(blocks);
  if (wanted.length === 0) {
    process.stderr.write(`✗ Could not parse any @font-face blocks from Google Fonts response.\n`);
    return 2;
  }

  // Make sure we use the family name Google returned (may differ in casing).
  const canonical = wanted[0].family || family;
  const fontId    = crypto.randomUUID();

  process.stdout.write(`▸ Installing "${canonical}" — ${wanted.length} variants under font-id ${fontId}\n`);

  let ok = 0, failed = 0;
  for (const v of wanted) {
    try {
      const buf   = await downloadFontFile(v.url);
      const mtype = v.format === 'woff2' ? 'font/woff2'
                  : v.format === 'woff'  ? 'font/woff'
                  : v.format === 'truetype' ? 'font/ttf'
                  : v.format === 'opentype' ? 'font/otf'
                  : 'font/woff2';
      await installVariant(client, teamId, fontId, { ...v, family: canonical }, buf, mtype);
      process.stdout.write(`  ✓ ${v.style.padEnd(7)} ${String(v.weight).padStart(3)}  ${(buf.length / 1024).toFixed(1).padStart(6)} KiB  ${mtype}\n`);
      ok++;
    } catch (e) {
      process.stderr.write(`  ✗ ${v.style.padEnd(7)} ${String(v.weight).padStart(3)}  FAILED — ${e.message}\n`);
      failed++;
    }
  }

  // Verify.  Note: Accept: application/json triggers Penpot's camelCase
  // response transformer, so the key is `fontFamily` not `font-family`.
  process.stdout.write(`▸ Re-fetching font-variants to verify\n`);
  const after     = await client.rpc('get-font-variants', { 'team-id': teamId });
  const famOf     = v => (v['font-family'] || v.fontFamily || '').toLowerCase();
  const nowHas    = (after || []).some(v => famOf(v) === canonical.toLowerCase());
  const newCount  = (after || []).filter(v => famOf(v) === canonical.toLowerCase()).length;

  if (nowHas) {
    process.stdout.write(`✓ "${canonical}" is now in Penpot (${newCount} variants).  Refresh Penpot to see it in penpot.fonts.all.\n`);
  } else {
    process.stderr.write(`✗ "${canonical}" did not show up in get-font-variants after install.\n`);
    return 3;
  }

  if (failed > 0) {
    process.stderr.write(`  WARNING: ${failed} of ${wanted.length} variant uploads failed.  Family is registered but partial.\n`);
    return 3;
  }
  return 0;
}

async function listInstalled() {
  const { penpotUrl, email, password } = readConfigOrDie();
  const client = new PenpotClient(penpotUrl);
  await client.login(email, password);
  const profile  = await client.rpc('get-profile', {});
  const teamId   = profile['default-team-id'] || profile.defaultTeamId;
  const variants = await client.rpc('get-font-variants', { 'team-id': teamId });
  const families = new Map();
  for (const v of (variants || [])) {
    const fam = (v['font-family'] || v.fontFamily || '').trim();
    if (!fam) continue;
    families.set(fam, (families.get(fam) || 0) + 1);
  }
  const rows = [...families.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  process.stdout.write(`${rows.length} custom font families in team ${teamId}:\n`);
  for (const [fam, n] of rows) {
    process.stdout.write(`  ${fam.padEnd(34)} ${n} variant${n === 1 ? '' : 's'}\n`);
  }
  return 0;
}

// ─── main ────────────────────────────────────────────────────────────────

async function main() {
  if (args.length === 0 || args.includes('--help') || args.includes('-h')) {
    printHelp();
    return 0;
  }
  if (args.includes('--list-installed')) {
    return await listInstalled();
  }
  const family = args.find(a => !a.startsWith('-'));
  if (!family) {
    printHelp();
    return 1;
  }
  return await installFamily(family);
}

main()
  .then(code => process.exit(code || 0))
  .catch(err => {
    process.stderr.write(`install-font: ${err.message}\n`);
    if (process.env.DEBUG) process.stderr.write((err.stack || '') + '\n');
    process.exit(1);
  });
