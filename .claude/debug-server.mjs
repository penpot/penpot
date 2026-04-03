/**
 * Minimal debug log server for Figma plugin debugging.
 * Receives POST requests from browser-side code (code.ts sandbox + UI iframe)
 * and appends structured entries to .claude/debug.log.
 *
 * Usage: node .claude/debug-server.mjs
 * Listens on port 7246 (to avoid conflict with any existing 7245 endpoint).
 */
import { createServer } from 'node:http';
import { appendFileSync, writeFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const LOG_PATH = resolve(__dirname, 'debug.log');
const PORT = 7246;

// Clear log on startup
writeFileSync(LOG_PATH, '');

const server = createServer((req, res) => {
  // CORS headers for iframe/sandbox fetch
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  if (req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try {
        const entry = JSON.parse(body);
        const line = `[${new Date().toISOString()}] [DEBUG ${entry.h ?? '?'}] [${entry.loc ?? '?'}] ${entry.msg ?? ''} ${entry.data ? JSON.stringify(entry.data) : ''}\n`;
        appendFileSync(LOG_PATH, line);
        process.stdout.write(line);
      } catch {
        appendFileSync(LOG_PATH, `[${new Date().toISOString()}] [RAW] ${body}\n`);
      }
      res.writeHead(200, { 'Content-Type': 'text/plain' });
      res.end('ok');
    });
    return;
  }

  res.writeHead(404);
  res.end();
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`Debug server listening on http://127.0.0.1:${PORT}`);
  console.log(`Logging to ${LOG_PATH}`);
});
