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
| `build-live-dom-canvas.mjs` | Walks the live DOM (Playwright, parallel page harvest) and recreates each page on its Penpot board as editable `createText` / `createRectangle` shapes — hyperlinks get a link colour + underline + href in the shape name. Flips `diagnose-live-render` from SCREENSHOT to LIVE-ISH. |
| `diagnose-live-render.mjs` | Reports whether each board is showing a live render or just a screenshot. Diffs canvas-shapes (via MCP) against live-DOM (via Playwright) and probes the iframe plugin. `--json` for machine output, `--page <name>` for a single page. |
| `portfolio-watcher.mjs` | Watches the portfolio's `src/` directory and re-runs the pipeline on save (debounced). |
| `webhook-server.mjs` | Listens for Vercel deploy-succeeded and GitHub push webhooks on port 9090, then enqueues a pipeline run for the new URL. Verifies HMAC if secrets are set. |
| `penpot-bridge.mjs` | Persistent headless Chromium that *tries* to keep the Penpot MCP plugin connected. **Caveat:** headless Chromium drops the plugin link frequently (60+ reloads/hour seen); for stable work, keep a real browser tab open at `auto-login.html`. Status JSON on `localhost:9002`. |
| `live-embed-check.mjs` | Health check for portfolio + MCP REPL. Falls back to running the pipeline if the live embed isn't reachable. |
| `live-preview-plugin/` | A tiny Penpot plugin (single `index.html`) that iframes the live portfolio with Home / Consulting / Blog tabs. Cross-origin iframe contents are opaque to Penpot — it's for the human, not for plugin-side DOM inspection. |
| `live-preview-server.mjs` | Serves the plugin above at `:9005`. Detached on `nohup` + `disown` so it survives the shell exiting. |
| `launch-all.sh` | One launcher with targets for `penpot`, `portfolio`, `bridge`, `watcher`, `webhook`, `live-preview`, `sync`, `stop-sync`, `screenshots`. |
| `penpot-launch.sh` | Brings up Penpot Docker, copies `auto-login.html` into the frontend container, opens the auto-login page. |
| `import-to-penpot.sh` | One-shot CLI: screenshot any URL/local-port/GitHub repo and push the frames into Penpot. |
| `portfolio-sync.config.json` | Single config: portfolio path + URL, webhook port + secrets, page list. Re-read on every webhook request. |

---

## First-time setup

```bash
# 1. From the repo root, bring up Penpot:
./tools/portfolio-sync/launch-all.sh penpot

# 1.5. One-time: install Chromium for the Playwright in this repo's node_modules
# (build-live-dom-canvas.mjs and diagnose-live-render.mjs need it):
(cd ./tools/portfolio-sync && npx playwright install chromium)

# 2. Point portfolio-sync at your site:
$EDITOR ./tools/portfolio-sync/portfolio-sync.config.json
# Set portfolio_dir + portfolio_local

# 3. (Optional) serve the live-preview plugin so you can register it in Penpot:
./tools/portfolio-sync/launch-all.sh live-preview
# In Penpot: Plugin Manager → Add custom plugin → http://localhost:9005
```

---

## Daily use

```bash
# Watch portfolio source → auto-resync canvas on save:
./tools/portfolio-sync/launch-all.sh watcher

# One-shot screenshot pass against a Vercel deploy:
./tools/portfolio-sync/import-to-penpot.sh --url https://my-site.vercel.app

# Rebuild the canvas from the LIVE DOM (editable text + hyperlinks, not screenshots):
node ./tools/portfolio-sync/build-live-dom-canvas.mjs
# flags: --page home              one page only
#        --reset-board            drop the screenshot backdrop too
#        --preserve-prefix <str>  shapes whose name starts with this prefix
#                                 survive the wipe (default "preserve-")
#        --batch <n>              MCP batch size (default 24)

# Check whether each board is showing a live render or a screenshot:
node ./tools/portfolio-sync/diagnose-live-render.mjs
# flags: --json   (machine-readable), --page home   (one page only)

# Stop background services:
./tools/portfolio-sync/launch-all.sh stop-sync
```

**Important:** `build-live-dom-canvas` and `diagnose-live-render` both call the
Penpot MCP plugin, which requires a connected workspace tab. Keep a browser open
on `http://localhost:9001/auto-login.html` — that page logs you in and
auto-starts the MCP plugin. The headless `penpot-bridge` *tries* to do this
without a real tab, but reliably drops the connection (60+ reloads/hour); treat
it as a fallback, not a primary.

---

## Architecture sketch

```
  portfolio src/         Vercel/GitHub          You (manual)
       │                      │                        │
       ▼                      ▼                        ▼
  portfolio-watcher     webhook-server         import-to-penpot.sh
       └──────┬───────────────┴────────────────────────┘
              ▼
   ┌─────────────────────────────────────────────────┐
   │ screenshot-pipeline.mjs        (image fills)    │
   │ build-live-dom-canvas.mjs      (createText)     │  ← two flavours
   └─────────────────────┬───────────────────────────┘
                         │
                         │ headless Chromium reads portfolio
                         ▼
              Penpot MCP HTTP API (:4401)
                         │
                         │ requires a connected workspace tab
                         ▼
              Penpot workspace canvas

  diagnose-live-render.mjs walks both ends of that arrow and reports the gap.
```

Two flavours of canvas sync:
- **screenshot-pipeline** drops flat PNGs onto the canvas. Fast, low fidelity,
  not selectable. Good for showing the rendered output as a reference.
- **build-live-dom-canvas** walks the live DOM and recreates each visible
  heading / paragraph / link / button / control as a Penpot shape with the
  right position, font, and (for links) the href in the shape name. Editable
  on the canvas. Use this when you actually want to interact with the layout.

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

---

## Run from scratch (no Claude required)

Goal: an outsider with a fresh clone ends up with `http://localhost:9006/`
rendering the live canvas (Penpot-editor chrome around it) by running a few
documented commands. No hand-fixing between steps.

1. Clone the Penpot fork and `cd` into it.
2. Install Playwright + Chromium for the toolkit:
   ```bash
   cd ~/penpot/tools/portfolio-sync && npm i playwright && npx playwright install chromium
   ```
3. Copy the config and set `portfolio_dir` to the absolute path of your
   portfolio repo:
   ```bash
   $EDITOR ./portfolio-sync.config.json
   ```
4. Bring up Penpot. This auto-opens `auto-login.html` in your default browser —
   keep that tab open for the rest of the session (it hosts the MCP plugin):
   ```bash
   ./launch-all.sh penpot
   ```
5. Start the portfolio dev server:
   ```bash
   ./launch-all.sh portfolio
   ```
6. Start the sync stack (bridge + watcher + webhook + live-preview + html-render):
   ```bash
   ./launch-all.sh sync
   ```
7. Run the standalone-fidelity test. Exit 0 means the canvas at `:9006`
   renders faithfully with Penpot chrome, configured pages in order, and
   regenerates on each request — no Claude required:
   ```bash
   node test-render.mjs
   ```
8. Open the live render:
   ```bash
   open http://localhost:9006/
   ```

If `test-render.mjs` exits non-zero, the test output names which check failed
— that's a real signal, not a test bug. See exit codes in the file header
(0 pass, 1 render gap, 2 chrome gap, 3 plumbing).
