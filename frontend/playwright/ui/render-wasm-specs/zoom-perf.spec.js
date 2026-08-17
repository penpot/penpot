import { test } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";
import fs from "fs";
import path from "path";

/**
 * Zoom performance capture. Not an assertion test — it drives a fixed zoom
 * sequence and writes a Chrome trace, so two builds can be compared under an
 * identical interaction.
 *
 * Run one build, stash/checkout the other, run again, then diff the traces:
 *
 *   PERF_LABEL=branch  npx playwright test zoom-perf --project render-wasm --workers=1
 *   PERF_LABEL=develop npx playwright test zoom-perf --project render-wasm --workers=1
 *   python3 playwright/scripts/trace-compare.py \
 *       perf-traces/zoom-perf-branch.json perf-traces/zoom-perf-develop.json
 *
 * The bundled fixtures are small (<150 shapes, most with shadows) and will not
 * reproduce the dense-tile case that `max_per_tile` flagged. For the decisive
 * comparison, save the `get-file` response of the real document under
 * playwright/data/ and point PERF_GET_FILE at it (PERF_PAGE_NAME too, if its
 * first page is not called "Page 1").
 */

const LABEL = process.env.PERF_LABEL ?? "run";
const CYCLES = Number(process.env.PERF_CYCLES ?? 6);
const STEPS = Number(process.env.PERF_STEPS ?? 20);
const STEP_DELAY = Number(process.env.PERF_STEP_DELAY ?? 16);
const SETTLE = Number(process.env.PERF_SETTLE ?? 600);
const TRACE = process.env.PERF_TRACE !== "0";
// zoom | pan | both. Penpot zooms on ctrl+wheel and pans on plain wheel
// (viewport/actions.cljs: `if (or ctrl? mod?) schedule-zoom! else
// schedule-scroll!`), so both gestures reuse the same wheel mechanism.
const MODE = process.env.PERF_MODE ?? "both";
const PAN_DELTA = Number(process.env.PERF_PAN_DELTA ?? 200);
// Zoom in before panning: at zoom-to-fit the whole document is on screen and
// panning just scrolls into empty space without crossing populated tiles.
const PAN_ZOOM_IN = Number(process.env.PERF_PAN_ZOOM_IN ?? 12);
const TIMEOUT = Number(process.env.PERF_TIMEOUT ?? 180000);

// File and page ids are read out of the payload by mockGetFile, so pointing
// PERF_GET_FILE at a different dump is the only change needed.
// Must be a fixture with no media assets: anything needing mockFileMediaAsset
// stalls on image fetches and `wasmSetObjectsFinished` never fires.
const GET_FILE =
  process.env.PERF_GET_FILE ?? "render-wasm/get-file-shapes-groups-boards.json";
const PAGE_NAME = process.env.PERF_PAGE_NAME ?? "Page 1";

// Excludes v8.inspector and v8.cpu_profiler on purpose: in the hand-captured
// traces those accounted for ~400k events and inflated main-thread cost on
// both sides.
const CATEGORIES = [
  "__metadata",
  "devtools.timeline",
  "disabled-by-default-devtools.timeline",
  "disabled-by-default-devtools.timeline.frame",
  "blink.user_timing",
  "benchmark",
  "cc",
];

// Headed is mandatory, not cosmetic: headless Chromium falls back to
// SwiftShader (CPU rasterizer), which makes every GPU measurement useless.
// Verified renderer strings on this machine:
//   headless -> ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device ...))
//   headed   -> ANGLE (Intel, Mesa Intel(R) Arc(tm) Graphics (MTL), ...)
test.use({ headless: false });

test.setTimeout(TIMEOUT);

test(`zoom perf capture [${LABEL}]`, async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);

  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile(GET_FILE);

  await workspace.goToWorkspace({ pageName: PAGE_NAME });
  await workspace.waitForFirstRenderWithoutUI();
  // Not waitForIdle(): requestIdleCallback never fires while the progressive
  // render loop keeps the main thread busy, and the test hangs to timeout.
  await page.waitForTimeout(2000);

  const box = await workspace.canvas.boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  await page.mouse.move(cx, cy);

  // Zoom to fit so every run starts from the same viewbox.
  await page.keyboard.press("Shift+1");
  await page.waitForTimeout(1500);

  // Not test-results/: Playwright wipes outputDir at the start of every run,
  // which would delete the first build's trace before the second one lands.
  const outDir = path.resolve("perf-traces");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, `zoom-perf-${LABEL}.json`);

  // browser.startTracing, not a raw CDPSession: Tracing is a browser-level
  // domain, and driving it from a page session deadlocks input — the
  // dataCollected flood blocks the same connection Playwright sends keys on.
  const browser = page.context().browser();
  if (TRACE) {
    await browser.startTracing(page, {
      path: out,
      categories: CATEGORIES,
      screenshots: false,
    });
  }

  const burst = async (dx, dy) => {
    for (let i = 0; i < STEPS; i++) {
      await page.mouse.wheel(dx, dy);
      await page.waitForTimeout(STEP_DELAY);
    }
    await page.waitForTimeout(SETTLE); // let the full-quality pass finish
  };

  if (MODE === "zoom" || MODE === "both") {
    // Zoom OUT first, then back in. Starting from zoom-to-fit and zooming in
    // never goes below fit scale, so scale-dependent level-of-detail paths
    // (imperceptible shadows/strokes) would never activate and the run would
    // measure nothing. Going out first spends half the phase at low scale.
    await page.keyboard.down("Control");
    for (let c = 0; c < CYCLES; c++) {
      await burst(0, 120); // zoom out, below fit
      await burst(0, -120); // zoom back in
    }
    await page.keyboard.up("Control");
    await page.waitForTimeout(500);
  }

  if (MODE === "pan" || MODE === "both") {
    await page.keyboard.down("Control");
    for (let i = 0; i < PAN_ZOOM_IN; i++) {
      await page.mouse.wheel(0, -120);
      await page.waitForTimeout(STEP_DELAY);
    }
    await page.keyboard.up("Control");
    await page.waitForTimeout(SETTLE);

    for (let c = 0; c < CYCLES; c++) {
      await burst(0, PAN_DELTA); // down
      await burst(PAN_DELTA, 0); // right
      await burst(0, -PAN_DELTA); // up
      await burst(-PAN_DELTA, 0); // left
    }
  }

  await page.waitForTimeout(1000);

  let events = [];
  if (TRACE) {
    const buf = await browser.stopTracing();
    const parsed = JSON.parse(buf.toString());
    events = Array.isArray(parsed) ? parsed : parsed.traceEvents;
  }

  // Inline summary so a single run is readable without the python analyzer.
  const durs = (name) =>
    events
      .filter((e) => e.name === name && e.ph === "X" && e.dur != null)
      .map((e) => e.dur);
  const stat = (v) => {
    if (!v.length) return "n=0";
    const s = [...v].sort((a, b) => a - b);
    const p = (q) => s[Math.min(s.length - 1, Math.round(q * (s.length - 1)))];
    const mean = v.reduce((a, b) => a + b, 0) / v.length;
    return (
      `n=${v.length} mean=${(mean / 1000).toFixed(2)}ms ` +
      `p50=${(p(0.5) / 1000).toFixed(2)} p95=${(p(0.95) / 1000).toFixed(2)} ` +
      `p99=${(p(0.99) / 1000).toFixed(2)} max=${(p(1) / 1000).toFixed(2)}`
    );
  };
  const dropped = events.filter((e) => e.name === "DroppedFrame").length;
  const zooms = events.filter(
    (e) => e.name === "set-view-box" && e.ph === "b",
  ).length;

  console.log(`\n[PERF ${LABEL}] trace -> ${out}`);
  console.log(`[PERF ${LABEL}] mode=${MODE} file=${GET_FILE.split("/").pop()} ` +
              `events=${events.length} viewbox=${zooms} droppedFrames=${dropped}`);
  console.log(`[PERF ${LABEL}] FireAnimationFrame ${stat(durs("FireAnimationFrame"))}`);
  console.log(`[PERF ${LABEL}] GPUTask           ${stat(durs("GPUTask"))}`);
});
