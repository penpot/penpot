import { test } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { WasmWorkspacePage } from "../ui/pages/WasmWorkspacePage";
import { installWebGLMemoryTracker } from "./gpu-memory/webgl-tracker.js";
import { sampleOs } from "./gpu-memory/os-sampler.js";

const env = process.env;

function git(...args) {
  try {
    return execFileSync("git", args, { encoding: "utf8" }).trim();
  } catch (_) {
    return null;
  }
}

const config = {
  label: env.PERF_LABEL ?? git("describe", "--always", "--dirty"),
  fixture: env.PERF_FIXTURE ?? "render-wasm/get-file-shadows.json",
  repeats: Number(env.PERF_REPEATS ?? 3),
  iterations: Number(env.PERF_ITERATIONS ?? 12),
  settleMs: Number(env.PERF_SETTLE_MS ?? 300),
  purgeProbe: env.PERF_PURGE_PROBE === "1",
  outDir: env.PERF_OUT_DIR ?? "playwright/perf/results",
};

// Page sizes cycled in the resize phase. Each change reaches the workspace
// ResizeObserver and reallocates the canvas drawing buffer and the render
// surfaces sized from it. Applied from CSS, not `setViewportSize`: headed
// WebKit on Linux can't resize its window.
const PAGE_SIZES = [
  { width: 1920, height: 1080 },
  { width: 1280, height: 720 },
  { width: 1600, height: 900 },
  { width: 1440, height: 810 },
];

async function setPageSize(page, size) {
  await page.evaluate((size) => {
    // The workspace root is sized in vw/vh, so body size alone does nothing.
    const root = document.querySelector(
      '[class*="workspace-content"]',
    )?.parentElement;
    if (!root) throw new Error("workspace root not found");
    for (const prop of ["inline-size", "block-size", "max-block-size"]) {
      const value = prop === "inline-size" ? size?.width : size?.height;
      root.style.setProperty(prop, size ? `${value}px` : "");
    }
  }, size);
}

async function wasmHash() {
  const bytes = await readFile("resources/public/js/render-wasm.wasm").catch(
    () => null,
  );
  return bytes && createHash("sha256").update(bytes).digest("hex").slice(0, 12);
}

// Waits until the renderer has not emitted a frame for `quietMs`.
async function settle(page, quietMs = config.settleMs, maxMs = 10000) {
  await page.evaluate(
    ({ quietMs, maxMs }) =>
      new Promise((resolve) => {
        const start = performance.now();
        let last = window.wasmRenderCount || 0;
        let lastChange = performance.now();
        const tick = () => {
          const now = performance.now();
          const count = window.wasmRenderCount || 0;
          if (count !== last) {
            last = count;
            lastChange = now;
          }
          if (now - lastChange >= quietMs || now - start >= maxMs) resolve();
          else requestAnimationFrame(tick);
        };
        requestAnimationFrame(tick);
      }),
    { quietMs, maxMs },
  );
}

test.describe.configure({ mode: "serial" });

test.beforeEach(async ({ page }) => {
  await page.addInitScript(installWebGLMemoryTracker);
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockRPC(
    page,
    "update-file?id=*",
    "text-editor/update-file.json",
  );
});

for (let repeat = 0; repeat < config.repeats; repeat++) {
  test(`GPU memory over a long edit session (repeat ${repeat})`, async ({
    page,
    browser,
    browserName,
  }, testInfo) => {
    test.setTimeout(15 * 60 * 1000);

    const workspace = new WasmWorkspacePage(page);
    const dir = `${config.outDir}/${config.label}`;
    await mkdir(dir, { recursive: true });
    const samples = [];
    const t0 = Date.now();

    const sample = async (phase, iteration = 0) => {
      await settle(page);
      const [web, os] = await Promise.all([
        page.evaluate(() => globalThis.__gpuMem?.snapshot() ?? null),
        sampleOs(),
      ]);
      samples.push({ phase, iteration, t: Date.now() - t0, web, os });
    };

    await workspace.setupEmptyFile();
    await workspace.mockGetFile(config.fixture);
    await workspace.goToWorkspace();
    await workspace.waitForFirstRenderWithoutUI();
    await sample("load");
    // Canvas captures from the first repeat show whether a change broke
    // rendering, not only how much memory it saved.
    const capture = async (name) => {
      if (repeat !== 0) return;
      await workspace.canvas.screenshot({
        path: `${dir}/${browserName}-${name}.png`,
      });
    };
    await capture("load");

    const base = page.viewportSize();

    for (let i = 0; i < config.iterations; i++) {
      await setPageSize(page, PAGE_SIZES[i % PAGE_SIZES.length]);
      await sample("resize", i);
    }
    await setPageSize(page, null);

    await page.mouse.move(base.width / 2, base.height / 2);
    for (let i = 0; i < config.iterations; i++) {
      await page.keyboard.down("ControlOrMeta");
      await page.mouse.wheel(0, i % 4 < 2 ? -400 : 400);
      await page.keyboard.up("ControlOrMeta");
      await sample("zoom", i);
    }

    for (let i = 0; i < config.iterations; i++) {
      const step = i % 4 < 2 ? 600 : -600;
      await page.mouse.wheel(i % 2 ? step : 0, i % 2 ? 0 : step);
      await sample("pan", i);
    }

    await page.keyboard.press("ControlOrMeta+a");
    for (let i = 0; i < config.iterations; i++) {
      await page.keyboard.press(i % 2 ? "Shift+ArrowLeft" : "Shift+ArrowRight");
      await sample("edit", i);
    }
    await page.keyboard.press("Escape");

    for (let i = 0; i < 2; i++) {
      await page.waitForTimeout(3000);
      await sample("idle", i);
    }
    await capture("idle");

    if (config.purgeProbe) {
      await page.evaluate(() =>
        globalThis.app?.render_wasm?.api?.free_gpu_resources?.(),
      );
      await sample("purge");
    }

    const renderer = await page.evaluate(
      () => globalThis.__gpuMem?.rendererInfo() ?? null,
    );
    const result = {
      meta: {
        label: config.label,
        engine: browserName,
        engineVersion: browser.version(),
        project: testInfo.project.name,
        repeat,
        fixture: config.fixture,
        iterations: config.iterations,
        viewport: base,
        dpr: testInfo.project.use.deviceScaleFactor ?? 1,
        renderer,
        commit: git("rev-parse", "--short", "HEAD"),
        wasm: await wasmHash(),
        platform: process.platform,
        date: new Date().toISOString(),
      },
      samples,
    };

    await writeFile(
      `${dir}/${browserName}-r${repeat}.json`,
      JSON.stringify(result, null, 2),
    );
  });
}
