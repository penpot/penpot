// Unscored correctness smoke. Request routing serves local files without a service.
// Optional screenshots belong only to this diagnostic process, never scored runs.
import { chromium } from "playwright";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { scenarios } from "../../resources/wasm-playground/scenes/index.js";
import { collectCases } from "../../resources/wasm-playground/benchmarks/contract.js";
import { bounded } from "../renderer-benchmarks.js";

const { values } = parseArgs({
  options: {
    screenshots: { type: "string" },
    headed: { type: "boolean", default: false },
    filter: { type: "string", default: "" },
  },
});
const frontend = fileURLToPath(new URL("../../", import.meta.url));
const cases = collectCases(scenarios, 42, values.filter);
const server = await chromium.launchServer({
  headless: !values.headed,
  args: ["--enable-gpu"],
});
const browser = await chromium.connect(server.wsEndpoint());
try {
  if (values.screenshots)
    await fs.mkdir(values.screenshots, { recursive: true });
  for (const c of cases) {
    const context = await browser.newContext({
      viewport: { width: 1920, height: 1080 },
      deviceScaleFactor: 2,
    });
    await context.route("http://benchmark.invalid/**", async (route) => {
      const pathname = new URL(route.request().url()).pathname;
      const base = pathname.startsWith("/wasm-playground/")
        ? path.join(frontend, "resources")
        : path.join(frontend, "resources/public");
      const target = path.resolve(base, `.${pathname}`);
      if (!target.startsWith(`${base}/`)) return route.abort();
      try {
        await route.fulfill({ path: target });
      } catch {
        await route.fulfill({ status: 404, body: "Missing diagnostic asset" });
      }
    });
    const page = await context.newPage();
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await page.goto("http://benchmark.invalid/wasm-playground/benchmark.html");
    await page.waitForFunction(
      () => globalThis.rendererBenchmarkReady === true,
      null,
      { timeout: 30000 },
    );
    const attempt = await bounded(
      page.evaluate(
        ([c, o]) => globalThis.rendererBenchmark.runAttempt(c, o),
        [
          c,
          {
            viewport: { width: 1920, height: 1080 },
            dpr: 2,
            timeoutMs: 30000,
            moduleUrl: "http://benchmark.invalid/js/render-wasm-benchmark.js",
          },
        ],
      ),
      65000,
    );
    if (attempt.status === "failed" || errors.length)
      throw new Error(`${c.id}: ${attempt.error ?? errors.join("; ")}`);
    if (attempt.slices.at(-1)?.frameType !== 2)
      throw new Error(`${c.id}: no Full completion`);
    if (values.screenshots && c.group === "fresh-module-context")
      await page.screenshot({
        path: path.join(values.screenshots, `${c.scenarioId}.png`),
      });
    console.log(
      `PASS ${c.id}; ${attempt.environment.webgl.renderer}; ${attempt.slices.length} render calls (unscored)`,
    );
    await page.evaluate(() => globalThis.rendererBenchmark.dispose());
    await bounded(context.close(), 5000);
  }
} finally {
  try {
    await bounded(browser.close(), 5000);
  } catch {
    server.process()?.kill("SIGKILL");
  }
  try {
    await bounded(server.close(), 5000);
  } catch {
    server.process()?.kill("SIGKILL");
  }
}
