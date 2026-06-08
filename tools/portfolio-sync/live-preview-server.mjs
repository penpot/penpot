#!/usr/bin/env node
// live-preview-server.mjs — Tiny static server for the Penpot "live preview"
// plugin. Serves tools/portfolio-sync/live-preview-plugin/index.html (and any
// sibling files) at http://127.0.0.1:9005/ so Penpot can register the plugin.
//
// Conventions match the other portfolio-sync background services
// (penpot-bridge, portfolio-watcher, webhook-server):
//   PID  → /tmp/live-preview.pid
//   log  → /tmp/live-preview.log
//
// Zero npm deps — built-in `http`, `fs`, `path`, `url` only.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const PLUGIN_DIR = path.join(__dirname, 'live-preview-plugin');
const INDEX_FILE = path.join(PLUGIN_DIR, 'index.html');
const HOST = '127.0.0.1';
const PORT = 9005;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js':   'application/javascript; charset=utf-8',
  '.mjs':  'application/javascript; charset=utf-8',
  '.css':  'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png':  'image/png',
  '.jpg':  'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg':  'image/svg+xml',
  '.ico':  'image/x-icon',
};

function safeResolve(reqPath) {
  // Strip query string, decode, then map "/" → index.html.
  let p = reqPath.split('?')[0];
  try { p = decodeURIComponent(p); } catch { /* keep raw */ }
  if (p === '/' || p === '') return INDEX_FILE;
  // Prevent path traversal — resolve and confirm we stay under PLUGIN_DIR.
  const resolved = path.normalize(path.join(PLUGIN_DIR, p));
  if (!resolved.startsWith(PLUGIN_DIR)) return null;
  return resolved;
}

const server = http.createServer((req, res) => {
  // CORS — Penpot embeds plugins from a different origin.
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, { 'Content-Type': 'text/plain' });
    res.end('Method Not Allowed');
    return;
  }

  const filePath = safeResolve(req.url || '/');
  if (!filePath) {
    res.writeHead(403);
    res.end();
    return;
  }

  fs.stat(filePath, (err, stat) => {
    if (err || !stat.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end();
      return;
    }
    const ext = path.extname(filePath).toLowerCase();
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': stat.size,
      'Cache-Control': 'no-store',
    });
    if (req.method === 'HEAD') { res.end(); return; }
    const stream = fs.createReadStream(filePath);
    stream.on('error', () => { try { res.end(); } catch {} });
    stream.pipe(res);
  });
});

server.on('error', (err) => {
  console.error('[live-preview] server error:', err.message);
  // Don't crash the process — let it stay up if e.g. a single request errored.
});

server.listen(PORT, HOST, () => {
  console.log(`[live-preview] listening on http://${HOST}:${PORT}`);
  console.log(`[live-preview] serving: ${INDEX_FILE}`);
});

// Keep process alive on stray errors so it survives weird requests.
process.on('uncaughtException', (err) => {
  console.error('[live-preview] uncaughtException:', err && err.stack || err);
});
process.on('unhandledRejection', (err) => {
  console.error('[live-preview] unhandledRejection:', err);
});
