# portfolio-sync

A bundle of automation that turns this Penpot fork into a live-reference workspace
for an external website. Edit the site, the canvas updates; deploy the site, the
canvas updates; open Penpot, a live iframe of the site is already on the panel.

Built originally for a static Astro portfolio at `localhost:4321` mirrored into a
Penpot file, but the source can be any URL: local dev server, Vercel deploy, or a
plain GitHub page.

---

## Pieces

| File | What it does |
|---|---|
| `screenshot-pipeline.mjs` | Headless Playwright captures page screenshots and imports each one into the currently-open Penpot file via the MCP `import_image` tool. |
| `portfolio-watcher.mjs` | Watches the portfolio's `src/` directory and re-runs the pipeline on save (debounced). |
| `webhook-server.mjs` | Listens for Vercel deploy-succeeded and GitHub push webhooks on port 9090, then enqueues a pipeline run for the new URL. Verifies HMAC if secrets are set. |
| `penpot-bridge.mjs` | Persistent headless Chromium that keeps the Penpot MCP plugin connected 24/7. Exposes status on `localhost:9002`. |
| `live-embed-check.mjs` | Health check for portfolio + MCP REPL. Falls back to running the pipeline if the live embed isn't reachable. |
| `live-preview-plugin/` | A tiny Penpot plugin (single `index.html`) that iframes the live portfolio with Home / Consulting / Blog tabs. Register it in Penpot at `http://localhost:9005`. |
| `launch-all.sh` | One launcher with targets for `penpot`, `portfolio`, `bridge`, `watcher`, `webhook`, `sync`, `stop-sync`, `screenshots`. |
| `penpot-launch.sh` | Brings up Penpot Docker, copies `auto-login.html` into the frontend container, opens the auto-login page. |
| `import-to-penpot.sh` | One-shot CLI: screenshot any URL/local-port/GitHub repo and push the frames into Penpot. |
| `portfolio-sync.config.json` | Single config: portfolio path + URL, webhook port + secrets, page list. Re-read on every webhook request. |

---

## First-time setup

```bash
# 1. From the repo root, bring up Penpot:
./tools/portfolio-sync/launch-all.sh penpot

# 2. Point portfolio-sync at your site:
$EDITOR ./tools/portfolio-sync/portfolio-sync.config.json
# Set portfolio_dir + portfolio_local

# 3. (Optional) serve the live-preview plugin so you can register it in Penpot:
#    In Penpot: Plugin Manager → Add custom plugin → http://localhost:9005
node -e "
const http=require('http'),fs=require('fs');
http.createServer((_,res)=>{
  res.writeHead(200,{'Content-Type':'text/html'});
  fs.createReadStream('./tools/portfolio-sync/live-preview-plugin/index.html').pipe(res);
}).listen(9005);"
```

---

## Daily use

```bash
# Watch portfolio source → auto-resync canvas on save:
./tools/portfolio-sync/launch-all.sh watcher

# One-shot screenshot pass against a Vercel deploy:
./tools/portfolio-sync/import-to-penpot.sh --url https://my-site.vercel.app

# Stop background services:
./tools/portfolio-sync/launch-all.sh stop-sync
```

---

## Architecture sketch

```
  portfolio src/        Vercel/GitHub          You (manual)
       │                     │                        │
       ▼                     ▼                        ▼
  portfolio-watcher    webhook-server         import-to-penpot.sh
       └──────┬──────────────┴────────────────────────┘
              ▼
       screenshot-pipeline.mjs
              │
              │ headless Chromium → PNG → docker cp →
              ▼
       Penpot MCP HTTP API (:4401)
              │
              ▼
       Penpot workspace (image rectangles on canvas)
```

The `penpot-bridge` keeps a headless Penpot tab open so the MCP plugin's REPL on
`:4403` never goes cold. Without it, you'd have to manually open the workspace
each time you want a sync run.

---

## Ports

| Port | What's there |
|---|---|
| 4321 | Portfolio dev server |
| 9001 | Penpot frontend |
| 9002 | penpot-bridge status JSON |
| 9005 | Live Preview plugin (when served locally) |
| 9090 | webhook-server (Vercel/GitHub) |
| 4401 | Penpot MCP HTTP API |
| 4402 | Penpot MCP WebSocket bridge |
| 4403 | Penpot MCP REPL HTTP |

---

## Notes

- All scripts resolve their paths from their own location, so the bundle is
  portable: move `tools/portfolio-sync/` anywhere and it still works.
- `portfolio-sync.config.json` is read on every webhook request — change secrets
  or page lists without restarting the server.
- Screenshots are kept full-resolution (1440 wide). An earlier version downscaled
  via `sips -Z 1200` which destroyed aspect ratio on tall pages; the current
  pipeline only caps at `>12000px` tall via height-leading resample.
