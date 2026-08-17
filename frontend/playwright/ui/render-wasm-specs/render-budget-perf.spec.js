import { test } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";
import { execSync } from "node:child_process";
import fs from "fs";
import path from "path";

/**
 * Render-budget and repaint-count capture. Not an assertion test: it drives a
 * fixed set of gestures and records, per phase, how long each `_render` blocked
 * (split by rAF vs the input/timer path) and the exact WASM render counters
 * (render-wasm/src/render/counters.rs).
 *
 * Read the counters first — they are exact and do not drift between runs, while
 * the millisecond numbers move with GPU/driver/thermal state. `empty_tile_ratio`
 * says whether the gestures were over content at all.
 *
 *   PERF_LABEL=before npx playwright test render-budget-perf \
 *       --project render-wasm --workers=1
 *   # ...change something, and rebuild the WASM...
 *   PERF_LABEL=after  npx playwright test render-budget-perf \
 *       --project render-wasm --workers=1
 *   python3 playwright/scripts/render-budget-compare.py \
 *       perf-traces/render-budget-before.json perf-traces/render-budget-after.json
 *
 * Without a rebuild between runs the second one silently measures the old .wasm.
 * PERF_GET_FILE points at any `get-file` dump under playwright/data/, as long as
 * it has no media assets: those stall on image fetches and
 * `wasmSetObjectsFinished` never fires.
 */

const LABEL = process.env.PERF_LABEL ?? "run";
const GET_FILE =
  process.env.PERF_GET_FILE ?? "render-wasm/get-file-shadows.json";
const PAGE_NAME = process.env.PERF_PAGE_NAME ?? "Page 1";
// `+` presses after zoom-to-fit, each exactly `min(z * 1.3, 200)`
// (data/workspace/zoom.cljs) => 5 puts the document at 3.7 viewports across.
// Not ctrl+wheel: a notch is 1.68x, `schedule-zoom!` compounds notches landing in
// the same rAF, and ~10 notches hit the 200x ceiling where the rest are no-ops.
const ZOOM_STEPS = Number(process.env.PERF_ZOOM_STEPS ?? 5);
const CYCLES = Number(process.env.PERF_CYCLES ?? 4);
const STEPS = Number(process.env.PERF_STEPS ?? 16);
const STEP_DELAY = Number(process.env.PERF_STEP_DELAY ?? 16);
const SETTLE = Number(process.env.PERF_SETTLE ?? 800);
// Pan amplitude per burst, as a fraction of the viewport: 0.4 crosses a 512px
// tile boundary while staying inside a document 3.7 viewports wide.
const PAN_TRAVEL = Number(process.env.PERF_PAN_TRAVEL ?? 0.4);
const ZOOM_NOTCHES = Number(process.env.PERF_ZOOM_NOTCHES ?? 3);
// Pinned: tile counts scale with viewport and DPR, so runs at different sizes
// are not comparable.
const VIEWPORT_W = Number(process.env.PERF_VIEWPORT_W ?? 1440);
const VIEWPORT_H = Number(process.env.PERF_VIEWPORT_H ?? 900);
const DPR = Number(process.env.PERF_DPR ?? 1);
// wheel-pan | drag-pan | zoom, comma separated. Default runs all three.
const PHASES = (process.env.PERF_PHASES ?? "wheel-pan,drag-pan,zoom")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

// Headed is mandatory, not cosmetic: headless Chromium falls back to
// SwiftShader (CPU rasterizer), which makes every timing below meaningless.
// Same reasoning as zoom-perf.spec.js.
test.use({
  headless: false,
  viewport: { width: VIEWPORT_W, height: VIEWPORT_H },
  deviceScaleFactor: DPR,
});
test.setTimeout(Number(process.env.PERF_TIMEOUT ?? 300000));

/**
 * Wraps the WASM `_render` export. Must be installed as an init script, before
 * any page code runs.
 *
 * Hooks `WebAssembly.instantiate*` rather than the CLJS-side module object
 * (`app.render_wasm.wasm.internal_module`): that path only resolves in a dev
 * build, and `resources/public` may hold a release bundle, where shadow mangles
 * every namespace. The FFI boundary is stable in both.
 *
 * Depth-counting rAF rather than a boolean: `request-render` schedules through
 * timers/raf and a render can request the next one from inside the callback.
 */
const renderHook = () => {
  const log = (globalThis.__renderLog = []);
  globalThis.__renderHookInstalled = false;

  let depth = 0;
  const raf = globalThis.requestAnimationFrame.bind(globalThis);
  globalThis.requestAnimationFrame = (cb) =>
    raf((t) => {
      depth++;
      try {
        return cb(t);
      } finally {
        depth--;
      }
    });

  const seen = new WeakMap();
  const wrapExports = (exports) => {
    if (!exports) return exports;
    // Rust `#[no_mangle] pub extern "C" fn render` lands in the instance as
    // `render`; the `_render` alias is added later by the Emscripten JS glue
    // when it copies exports onto Module.
    const key = ["render", "_render"].find(
      (k) => typeof exports[k] === "function",
    );
    if (!key) return exports;
    if (seen.has(exports)) return seen.get(exports);

    // Keep the raw exports around so the test can read the WASM render
    // counters (`perf_counter_*`). Same reason the hook lives here: this is the
    // only handle on the instance that survives a release build.
    globalThis.__wasmExports = exports;

    const orig = exports[key];
    const wrapped = function (ts, flags) {
      const inRaf = depth > 0;
      // Stack capture only for the calls under investigation; it is not free.
      const stack = inRaf ? null : new Error().stack;
      const t0 = performance.now();
      const r = orig.call(this, ts, flags);
      log.push({
        ms: +(performance.now() - t0).toFixed(2),
        flags,
        raf: inRaf,
        frame: r,
        caller: stack
          ?.split("\n")
          .slice(2)
          .find((l) => !/wrapped|renderHook/.test(l))
          ?.trim()
          ?.replace(/^at\s+/, "")
          ?.replace(/^.*\/(cljs-runtime|js)\//, ""),
      });
      return r;
    };

    // A plain copy, never a Proxy: wasm exports are frozen, and a `get` trap must
    // return the real value for a non-configurable data property, so proxying
    // `render` throws a TypeError on every call.
    const copy = Object.assign(Object.create(null), exports);
    copy[key] = wrapped;
    seen.set(exports, copy);
    globalThis.__renderHookInstalled = true;
    return copy;
  };

  const wrapInstance = (inst) =>
    inst instanceof WebAssembly.Instance
      ? new Proxy(inst, {
          get: (t, p, r) =>
            p === "exports" ? wrapExports(t.exports) : Reflect.get(t, p, r),
        })
      : inst;

  const streaming = WebAssembly.instantiateStreaming;
  if (streaming) {
    WebAssembly.instantiateStreaming = (...args) =>
      streaming(...args).then((res) => ({
        module: res.module,
        instance: wrapInstance(res.instance),
      }));
  }
  const instantiate = WebAssembly.instantiate;
  WebAssembly.instantiate = (...args) =>
    instantiate(...args).then((res) =>
      res instanceof WebAssembly.Instance
        ? wrapInstance(res)
        : { module: res.module, instance: wrapInstance(res.instance) },
    );
};

const drain = (page) =>
  page.evaluate(() => {
    const l = globalThis.__renderLog.slice();
    globalThis.__renderLog.length = 0;
    return l;
  });

/**
 * Mirrors `render::counters::NAMES` (render-wasm/src/render/counters.rs), in
 * order. `readCounters` asserts the length against `perf_counter_count()`, so a
 * counter added on the Rust side without updating this list fails the run
 * instead of silently shifting every label.
 *
 * Counts, unlike the millisecond stats above, are exact: they do not move
 * between runs on the same gestures, which is what makes them the primary
 * signal when comparing two builds.
 */
const COUNTER_NAMES = [
  "render_loop_starts",
  "render_loop_continues",
  "partial_yields",
  "tiles_painted",
  "tiles_cache_hit",
  "tiles_empty_skipped",
  "tiles_invalidated",
  "tile_cache_wipes",
  "tiles_discarded_inflight",
  "walker_visits",
  "walker_culled",
  "shape_paints",
  "shape_paints_direct",
  "surface_stack_composites",
  "surface_stack_draw_px",
  "surface_stack_clear_px",
  "paragraph_builds",
  "text_layouts",
  "doc_atlas_writes",
  "tile_atlas_writes",
  "cache_surface_writes",
  "tile_atlas_snapshots",
  "tile_atlas_snapshot_px",
  "frame_presents",
  "crop_entries_built",
  "crop_blits",
  "crop_rejected",
  "shape_tile_updates",
];

// Emscripten exposes the Rust symbol as-is on the instance; the `_`-prefixed
// alias only exists on the Module object, which a release build mangles out of
// reach — hence the `?? _name` fallback everywhere below.
const readCounters = (page) =>
  page.evaluate((names) => {
    const ex = globalThis.__wasmExports;
    if (!ex) return { error: "no wasm exports captured" };
    const pick = (n) => ex[n] ?? ex[`_${n}`];
    const get = pick("perf_counter_get");
    const count = pick("perf_counter_count");
    if (typeof get !== "function" || typeof count !== "function") {
      return { error: "perf_counter_* exports missing — rebuild the WASM" };
    }
    const n = count();
    if (n !== names.length) {
      return {
        error:
          `counter count mismatch: wasm=${n} spec=${names.length} — ` +
          "COUNTER_NAMES is out of sync with render::counters::NAMES",
      };
    }
    const out = {};
    for (let i = 0; i < n; i++) out[names[i]] = get(i);
    return out;
  }, COUNTER_NAMES);

const resetCounters = (page) =>
  page.evaluate(() => {
    const ex = globalThis.__wasmExports;
    const reset = ex?.perf_counters_reset ?? ex?._perf_counters_reset;
    if (typeof reset === "function") reset();
  });

/** Reads the counters for a phase and zeroes them for the next one. */
const drainCounters = async (page) => {
  const counters = await readCounters(page);
  await resetCounters(page);
  return counters;
};

// Derived ratios: the numbers that actually answer "are we painting the same
// thing more than once". Kept out of the Rust side so they can change without
// a rebuild.
const derive = (c) => {
  if (!c || c.error) return null;
  const div = (a, b) => (b ? +(a / b).toFixed(2) : 0);
  const tiles = c.tiles_painted;
  return {
    // Shape paints per tile painted. Grows with how much a shape's tile
    // footprint is over-estimated (margin culling) and with per-tile root
    // fan-out.
    shape_paints_per_tile: div(c.shape_paints, tiles),
    walker_visits_per_tile: div(c.walker_visits, tiles),
    // Fraction of walked nodes that painted nothing.
    culled_ratio: div(c.walker_culled, c.walker_visits),
    // Shapes that needed the full 1024² surface stack instead of drawing
    // straight into the tile.
    layered_paint_ratio: div(
      c.shape_paints - c.shape_paints_direct,
      c.shape_paints,
    ),
    // Whole-surface pixels moved per tile painted (a 512² tile is 262144 px).
    composite_px_per_tile: div(
      c.surface_stack_draw_px + c.surface_stack_clear_px,
      tiles,
    ),
    paragraph_builds_per_tile: div(c.paragraph_builds, tiles),
    text_layouts_per_tile: div(c.text_layouts, tiles),
    // Cache effectiveness: hits vs repaints, and how much was thrown away.
    tile_cache_hit_ratio: div(c.tiles_cache_hit, c.tiles_cache_hit + tiles),
    tiles_painted_per_present: div(tiles, c.frame_presents),
    // Share of visited tiles that hold no shape at all. High means the gestures
    // are running over blank canvas and the phase is not measuring anything —
    // check the zoom/pan amplitudes before reading anything else.
    empty_tile_ratio: div(
      c.tiles_empty_skipped,
      c.tiles_empty_skipped + c.tiles_cache_hit + tiles,
    ),
  };
};

const stat = (v) => {
  if (!v.length) return { n: 0 };
  const s = [...v].sort((a, b) => a - b);
  const p = (q) => s[Math.min(s.length - 1, Math.round(q * (s.length - 1)))];
  return {
    n: v.length,
    mean: +(v.reduce((a, b) => a + b, 0) / v.length).toFixed(2),
    p50: +p(0.5).toFixed(2),
    p95: +p(0.95).toFixed(2),
    max: +p(1).toFixed(2),
    total: +v.reduce((a, b) => a + b, 0).toFixed(1),
  };
};

const summarize = (calls) => {
  const sync = calls.filter((e) => !e.raf);
  const rafs = calls.filter((e) => e.raf);
  const byFlag = {};
  for (const e of sync) (byFlag[e.flags] ??= []).push(e.ms);
  const worst = [...sync].sort((a, b) => b.ms - a.ms)[0] ?? null;
  return {
    sync: stat(sync.map((e) => e.ms)),
    raf: stat(rafs.map((e) => e.ms)),
    syncByFlag: Object.fromEntries(
      Object.entries(byFlag).map(([f, v]) => [f, stat(v)]),
    ),
    worstSync: worst
      ? {
          ms: worst.ms,
          flags: worst.flags,
          frame: worst.frame,
          caller: worst.caller,
        }
      : null,
  };
};

test(`render budget perf [${LABEL}]`, async ({ page }) => {
  // A `_render` that raised did no work but still logs a cheap call, deflating
  // the phase. Recorded per phase rather than failing: some are pre-existing.
  const wasmErrors = [];
  page.on("console", (msg) => {
    if (msg.type() !== "error") return;
    const text = msg.text();
    if (/wasm-error|wasm-critical|WASM Error/.test(text)) {
      wasmErrors.push(text.slice(0, 300));
    }
  });
  page.on("pageerror", (err) => {
    if (/wasm/i.test(String(err))) wasmErrors.push(String(err).slice(0, 300));
  });
  const drainErrors = () => wasmErrors.splice(0, wasmErrors.length);

  await page.addInitScript(renderHook);
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

  const hooked = await page.evaluate(() => globalThis.__renderHookInstalled);
  if (!hooked) {
    throw new Error(
      "_render was never wrapped — the renderer did not instantiate through " +
        "WebAssembly.instantiate/instantiateStreaming, or the export was renamed",
    );
  }

  // Everything up to here is the load and its first full render: every visible
  // tile painted from an empty cache, no gesture, no cache hits.
  const loadCounters = await drainCounters(page);
  if (loadCounters.error) {
    throw new Error(`render counters unavailable: ${loadCounters.error}`);
  }
  const loadCalls = await drain(page);
  const loadErrors = drainErrors();
  const firstError = loadErrors.length ? `; first error: ${loadErrors[0]}` : "";
  if (loadCalls.length === 0) {
    throw new Error(
      `no _render calls recorded during load — the hook is not intercepting ` +
        `the renderer${firstError}`,
    );
  }
  if (loadCounters.tiles_painted === 0) {
    throw new Error(
      "load phase painted no tiles — _render ran but the document has no " +
        `shapes in any tile (fixture/page mismatch?)${firstError}`,
    );
  }

  const box = await workspace.canvas.boundingBox();
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  await page.mouse.move(cx, cy);

  // Same viewbox every run: fit the document, then zoom in a known factor
  // toward the canvas centre (`increase-zoom` centres on the mouse, which is
  // parked there). Content therefore surrounds the viewport on all sides and
  // every gesture below stays over shapes.
  await page.keyboard.press("Shift+1");
  await page.waitForTimeout(1200);

  // "=" rather than "+": both are bound to :increase-zoom (shortcuts.cljs), and
  // "=" needs no shift modifier for mousetrap to match.
  for (let i = 0; i < ZOOM_STEPS; i++) {
    await page.keyboard.press("=");
    await page.waitForTimeout(120);
  }
  await page.waitForTimeout(1500);

  await drain(page); // discard zoom-in setup
  await resetCounters(page);

  // A plain wheel delta pans the vbox by `delta / zoom` doc units
  // (`schedule-scroll!`), i.e. by `delta` screen pixels whatever the zoom. So
  // amplitudes are expressed in screen pixels, derived from the viewport.
  const panX = Math.round((box.width * PAN_TRAVEL) / STEPS);
  const panY = Math.round((box.height * PAN_TRAVEL) / STEPS);

  const wheelBurst = async (dx, dy) => {
    for (let i = 0; i < STEPS; i++) {
      await page.mouse.wheel(dx, dy);
      await page.waitForTimeout(STEP_DELAY);
    }
    await page.waitForTimeout(SETTLE);
  };

  const runners = {
    // Plain wheel pan, ending via the debounced `render-finish`. Zoom is stable,
    // so `allow_stop` is false and the progressive budget never applies.
    // Down/right/up/left closes the cycle, so it cannot drift off the document.
    "wheel-pan": async () => {
      for (let c = 0; c < CYCLES; c++) {
        await wheelBurst(0, panY);
        await wheelBurst(panX, 0);
        await wheelBurst(0, -panY);
        await wheelBurst(-panX, 0);
      }
    },

    // Space-drag pan. Ends on pointerup via `finish-panning` ->
    // maybe-view-interaction-end!, i.e. straight on the input path.
    // Drags out and returns, for the same reason as the wheel cycle.
    "drag-pan": async () => {
      const moves = 12;
      const dx = (box.width * PAN_TRAVEL) / moves;
      const dy = (box.height * PAN_TRAVEL) / moves;
      for (let c = 0; c < CYCLES; c++) {
        await page.keyboard.down("Space");
        await page.mouse.move(cx, cy);
        await page.mouse.down();
        for (let i = 1; i <= moves; i++) {
          await page.mouse.move(
            Math.round(cx - i * dx),
            Math.round(cy - i * dy),
          );
          await page.waitForTimeout(STEP_DELAY);
        }
        for (let i = moves - 1; i >= 0; i--) {
          await page.mouse.move(
            Math.round(cx - i * dx),
            Math.round(cy - i * dy),
          );
          await page.waitForTimeout(STEP_DELAY);
        }
        await page.mouse.up();
        await page.keyboard.up("Space");
        await page.waitForTimeout(SETTLE);
      }
    },

    // Ctrl+wheel zoom: `zoom_changed()` makes `allow_stop` true, so this is the
    // phase that exercises the progressive budget. In then out, keeping the
    // working zoom as the floor so it never zooms out into empty canvas.
    zoom: async () => {
      const ramp = async (delta) => {
        await page.keyboard.down("Control");
        for (let i = 0; i < ZOOM_NOTCHES; i++) {
          await page.mouse.wheel(0, delta);
          await page.waitForTimeout(STEP_DELAY);
        }
        await page.keyboard.up("Control");
        await page.waitForTimeout(SETTLE);
      };
      for (let c = 0; c < CYCLES; c++) {
        await ramp(-120); // in
        await ramp(120); // out, back to the working zoom
      }
    },
  };

  const phases = {
    load: {
      calls: loadCalls,
      summary: summarize(loadCalls),
      counters: loadCounters,
      ratios: derive(loadCounters),
      wasmErrors: loadErrors,
    },
  };
  let total = loadCalls.length;
  for (const name of PHASES) {
    const run = runners[name];
    if (!run) throw new Error(`unknown phase "${name}"`);
    await run();
    const calls = await drain(page);
    const counters = await drainCounters(page);
    total += calls.length;
    phases[name] = {
      calls,
      summary: summarize(calls),
      counters,
      ratios: derive(counters),
      wasmErrors: drainErrors(),
    };
  }

  // A run that recorded nothing is not a passing run — it means the gestures
  // never reached the renderer (wrong branch built, render-wasm flag off, or
  // the workspace fell back to the SVG viewport). Fail loudly rather than
  // writing an all-zero file that looks like a legitimate comparison baseline.
  if (total === 0) {
    throw new Error(
      "no _render calls recorded across any phase — the build under test is " +
        "probably not rendering through render-wasm",
    );
  }

  let rev = "unknown";
  try {
    rev = execSync("git rev-parse --short HEAD", { encoding: "utf-8" }).trim();
    const dirty = execSync("git status --porcelain", {
      encoding: "utf-8",
    }).trim();
    if (dirty) rev += "-dirty";
  } catch {}

  // Not test-results/: Playwright wipes outputDir at the start of every run,
  // which would delete the first run's data before the second one lands.
  const outDir = path.resolve("perf-traces");
  fs.mkdirSync(outDir, { recursive: true });
  const out = path.join(outDir, `render-budget-${LABEL}.json`);
  fs.writeFileSync(
    out,
    JSON.stringify(
      {
        label: LABEL,
        rev,
        date: new Date().toISOString(),
        file: GET_FILE,
        options: {
          ZOOM_STEPS,
          ZOOM_NOTCHES,
          CYCLES,
          STEPS,
          STEP_DELAY,
          SETTLE,
          PAN_TRAVEL,
          // Tile counts scale with the viewport, so two runs are only
          // comparable at the same size and DPR. The compare script refuses to
          // read across a mismatch.
          VIEWPORT: `${VIEWPORT_W}x${VIEWPORT_H}`,
          DPR,
        },
        counterNames: COUNTER_NAMES,
        phases,
      },
      null,
      2,
    ),
  );

  const fmt = (s) =>
    s.n
      ? `n=${s.n} mean=${s.mean}ms p50=${s.p50} p95=${s.p95} max=${s.max} total=${s.total}`
      : "n=0";
  console.log(
    `\n[PERF ${LABEL}] rev=${rev} file=${GET_FILE.split("/").pop()} ` +
      `viewport=${VIEWPORT_W}x${VIEWPORT_H}@${DPR}x`,
  );
  for (const [
    name,
    { summary, counters, ratios, wasmErrors: errs },
  ] of Object.entries(phases)) {
    console.log(`[PERF ${LABEL}] --- ${name}`);
    if (errs?.length) {
      console.log(
        `[PERF ${LABEL}]   !! ${errs.length} wasm error(s) in this phase, ` +
          `counters are understated: ${errs[0]}`,
      );
    }
    console.log(
      `[PERF ${LABEL}]   SYNC (blocks main thread) ${fmt(summary.sync)}`,
    );
    console.log(
      `[PERF ${LABEL}]   rAF                       ${fmt(summary.raf)}`,
    );
    if (summary.worstSync) {
      const w = summary.worstSync;
      console.log(
        `[PERF ${LABEL}]   worst sync ${w.ms}ms flags=${w.flags} ` +
          `frameType=${w.frame} @${w.caller ?? "?"}`,
      );
    }
    if (counters && !counters.error) {
      console.log(
        `[PERF ${LABEL}]   tiles painted=${counters.tiles_painted} ` +
          `cached=${counters.tiles_cache_hit} ` +
          `invalidated=${counters.tiles_invalidated} ` +
          `wipes=${counters.tile_cache_wipes} ` +
          `discarded=${counters.tiles_discarded_inflight}`,
      );
      console.log(
        `[PERF ${LABEL}]   shape paints=${counters.shape_paints} ` +
          `(${counters.shape_paints_direct} direct) ` +
          `walker visits=${counters.walker_visits} ` +
          `culled=${counters.walker_culled}`,
      );
      console.log(
        `[PERF ${LABEL}]   text builds=${counters.paragraph_builds} ` +
          `layouts=${counters.text_layouts} | ` +
          `composite px=${(counters.surface_stack_draw_px / 1e6).toFixed(1)}M ` +
          `clear px=${(counters.surface_stack_clear_px / 1e6).toFixed(1)}M`,
      );
      console.log(
        `[PERF ${LABEL}]   per tile: shapes=${ratios.shape_paints_per_tile} ` +
          `visits=${ratios.walker_visits_per_tile} ` +
          `composite=${(ratios.composite_px_per_tile / 1e6).toFixed(2)}M px ` +
          `| layered=${(ratios.layered_paint_ratio * 100).toFixed(0)}% ` +
          `cache hit=${(ratios.tile_cache_hit_ratio * 100).toFixed(0)}% ` +
          `empty=${(ratios.empty_tile_ratio * 100).toFixed(0)}%`,
      );
      if (ratios.empty_tile_ratio > 0.5) {
        console.log(
          `[PERF ${LABEL}]   !! over half the tiles in this phase are empty — ` +
            "the gestures are mostly over blank canvas, lower PERF_ZOOM_STEPS " +
            "or PERF_PAN_TRAVEL, or use a denser fixture",
        );
      }
    }
  }
  console.log(`[PERF ${LABEL}] wrote ${out}`);
});
