// Unscored browser fault checks. Uses prepared assets; manages no services.
import assert from "node:assert/strict";
import { chromium } from "playwright";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { bounded } from "../renderer-benchmarks.js";
import { scenarios } from "../../resources/wasm-playground/scenes/index.js";
import { collectCases } from "../../resources/wasm-playground/benchmarks/contract.js";

const frontend = fileURLToPath(new URL("../../", import.meta.url));
const server = await chromium.launchServer({
  headless: true,
  args: ["--enable-gpu"],
});
const process = server.process();
const browser = await chromium.connect(server.wsEndpoint());
const benchmarkCase = collectCases(scenarios, 42, "rects/default/pan")[0];
const options = {
  viewport: { width: 1920, height: 1080 },
  dpr: 2,
  timeoutMs: 30000,
  moduleUrl: "http://benchmark.invalid/fault-module.js",
};
try {
  for (const fault of [
    "unknown-frame",
    "context-loss",
    "timeout",
    "cancel-init",
    "cancel-frame",
    "blocked-wasm",
    "page-crash",
  ]) {
    const context = await browser.newContext({
      viewport: options.viewport,
      deviceScaleFactor: options.dpr,
    });
    try {
      await context.route("http://benchmark.invalid/**", async (route) => {
        const pathname = new URL(route.request().url()).pathname;
        if (pathname === "/fault-module.js") {
          await route.fulfill({
            contentType: "text/javascript",
            body: `
            import createModule from '/js/render-wasm-benchmark.js';
            export default async function() {
              const module = await createModule();
              if (${JSON.stringify(fault)} === 'cancel-init') await new Promise(resolve => { globalThis.releaseModule = resolve; });
              const render = module._render;
              module._render = (...args) => {
                if (${JSON.stringify(fault)} === 'unknown-frame') return 0;
                if (${JSON.stringify(fault)} === 'blocked-wasm') { while (true) {} }
                if (${JSON.stringify(fault)} === 'context-loss') document.querySelector('canvas').getContext('webgl2').getExtension('WEBGL_lose_context').loseContext();
                return render(...args);
              };
              return module;
            }`,
          });
          return;
        }
        const base = path.join(
          frontend,
          pathname.startsWith("/wasm-playground/")
            ? "resources"
            : "resources/public",
        );
        const target = path.resolve(base, `.${pathname}`);
        if (!target.startsWith(`${base}/`)) return route.abort();
        await route.fulfill({ path: target });
      });
      const page = await context.newPage();
      await page.goto(
        "http://benchmark.invalid/wasm-playground/benchmark.html",
      );
      await page.waitForFunction(() => globalThis.rendererBenchmarkReady);
      if (fault === "timeout")
        await page.evaluate(() => {
          globalThis.requestAnimationFrame = () => 1;
        });
      if (fault === "cancel-frame")
        await page.evaluate(() => {
          globalThis.requestAnimationFrame = () => {
            globalThis.framePending = true;
            return 1;
          };
        });
      if (fault === "page-crash") {
        const crashed = page.waitForEvent("crash");
        const cdp = await context.newCDPSession(page);
        void cdp.send("Page.crash").catch(() => {});
        await bounded(crashed, 5000);
        await assert.rejects(
          page.evaluate(() => 1),
          /crash|closed/i,
        );
      } else {
        // Attach rejection handling immediately; the watchdog may finish before injection.
        const outcome = bounded(
          page.evaluate(
            ([c, o]) => globalThis.rendererBenchmark.runAttempt(c, o),
            [benchmarkCase, options],
          ),
          fault === "blocked-wasm" || fault === "timeout" ? 2500 : 10000,
        ).then(
          (value) => ({ value }),
          (error) => ({ error }),
        );
        if (fault === "cancel-init" || fault === "cancel-frame") {
          await page.waitForFunction(
            (key) => Boolean(globalThis[key]),
            fault === "cancel-init" ? "releaseModule" : "framePending",
          );
          await page.evaluate(() => {
            globalThis.rendererBenchmark.dispose();
            globalThis.releaseModule?.();
          });
        }
        const result = await outcome;
        if (fault === "blocked-wasm" || fault === "timeout")
          assert.match(result.error?.message ?? "", /watchdog/);
        else {
          assert.equal(result.error, undefined);
          assert.equal(result.value.status, "failed");
          assert.match(
            result.value.error,
            fault === "unknown-frame"
              ? /Unexpected frame type/
              : fault === "context-loss"
                ? /context lost/
                : /canceled|Stale/,
          );
        }
      }
      console.log(`PASS ${fault} (unscored)`);
    } finally {
      await bounded(context.close(), 5000);
    }
  }
} finally {
  try {
    await bounded(browser.close(), 5000);
  } catch {
    process.kill("SIGKILL");
  }
  try {
    await bounded(server.close(), 5000);
  } catch {
    process.kill("SIGKILL");
  }
  assert.ok(
    process.exitCode !== null || process.signalCode !== null,
    "browser process must exit",
  );
}
