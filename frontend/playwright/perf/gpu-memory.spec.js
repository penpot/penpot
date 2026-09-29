import { test } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { createServer } from "node:http";
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
  // "x,y" in CSS px: select the element there instead of everything. On a
  // big page, moving every shape at once takes minutes per step.
  editTarget: env.PERF_EDIT_TARGET?.split(",").map(Number) ?? null,
  skiaCacheMb: env.PERF_SKIA_CACHE_MB ? Number(env.PERF_SKIA_CACHE_MB) : null,
  timeoutMin: Number(env.PERF_TIMEOUT_MIN ?? 15),
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

// Serves the fixture over plain HTTP. `route.fulfill` sends the body through
// the DevTools protocol, and a large fixture (tens of MB) drops the browser
// session.
let fixtureServer;

async function fixtureUrl() {
  if (!fixtureServer) {
    fixtureServer = createServer((_req, res) => {
      res.writeHead(200, {
        "content-type": "application/transit+json",
        "access-control-allow-origin": "*",
      });
      createReadStream(`playwright/data/${config.fixture}`).pipe(res);
    });
    await new Promise((resolve) =>
      fixtureServer.listen(0, "127.0.0.1", resolve),
    );
  }
  return `http://127.0.0.1:${fixtureServer.address().port}/get-file`;
}

test.afterAll(() => fixtureServer?.close());

// goToWorkspace waits for the page name, which differs per fixture.
async function fixturePageName(fixture) {
  const data = JSON.parse(await readFile(`playwright/data/${fixture}`, "utf8"));
  const pages = data["~:data"]?.["~:pages-index"] ?? {};
  return Object.values(pages)[0]?.["~:name"] ?? "Page 1";
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

test.beforeEach(async ({ page }) => {
  page.on("crash", () => console.error("[gpu-memory] page crashed"));
  page.on("pageerror", (e) => console.error(`[gpu-memory] ${e.message}`));
  await page.addInitScript(installWebGLMemoryTracker, {
    traceSize: env.PERF_TRACE_SIZE ?? null,
  });
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
    test.setTimeout(config.timeoutMin * 60 * 1000);

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
    const url = await fixtureUrl();
    // Registered last, so it wins over the fulfill route mockGetFile added.
    await page.route(/get-file\?/, (route) => route.continue({ url }));
    await workspace.goToWorkspace({
      pageName: await fixturePageName(config.fixture),
    });
    await workspace.waitForFirstRenderWithoutUI();
    if (config.skiaCacheMb) {
      // Set after the first render, so the load phase still runs on the
      // build's default budget.
      const applied = await page.evaluate((mb) => {
        const mod = globalThis.app?.common?.render_wasm?.wasm?.internal_module;
        if (typeof mod?._set_resource_cache_limit_mb !== "function")
          return false;
        mod._set_resource_cache_limit_mb(mb);
        return true;
      }, config.skiaCacheMb);
      if (!applied)
        throw new Error("wasm build lacks set_resource_cache_limit_mb");
    }
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

    if (config.editTarget) {
      const [ex, ey] = config.editTarget;
      await page.mouse.click(ex, ey);
    } else {
      await page.keyboard.press("ControlOrMeta+a");
    }
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
        skiaCacheMb: config.skiaCacheMb,
        editTarget: config.editTarget,
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
