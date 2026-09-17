// Unscored checks of the actual playground pages, including real pointer/wheel
// events. Expose the mounted adapter only in this diagnostic routing layer.
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { chromium } from "playwright";
import { bounded } from "../renderer-benchmarks.js";
import { scenarios } from "../../resources/wasm-playground/scenes/index.js";

const { values } = parseArgs({
  options: {
    filter: { type: "string", default: "" },
    headed: { type: "boolean", default: false },
  },
});
const frontend = fileURLToPath(new URL("../../", import.meta.url));
const selected = scenarios.filter((s) => s.id.includes(values.filter));
assert.ok(selected.length, "No matching scenes");
const server = await chromium.launchServer({
  headless: !values.headed,
  args: ["--enable-gpu"],
});
const browser = await chromium.connect(server.wsEndpoint());
try {
  for (const scenario of selected) {
    const context = await browser.newContext({
      viewport: { width: 1920, height: 1080 },
      deviceScaleFactor: 2,
    });
    try {
      await bounded(
        (async () => {
          await context.route("http://benchmark.invalid/**", async (route) => {
            const pathname = new URL(route.request().url()).pathname;
            const base = path.join(
              frontend,
              pathname.startsWith("/wasm-playground/")
                ? "resources"
                : "resources/public",
            );
            const target = path.resolve(base, `.${pathname}`);
            if (!target.startsWith(`${base}/`)) return route.abort();
            if (pathname === "/wasm-playground/js/lib.js") {
              const source = await fs.readFile(target, "utf8");
              await route.fulfill({
                contentType: "text/javascript",
                body:
                  source.replace(
                    "export async function mountScenario(",
                    "async function mountOriginalScenario(",
                  ) +
                  `
            export async function mountScenario(...args) {
              const adapter = await mountOriginalScenario(...args);
              const render = adapter.module._render;
              const view = adapter.module._set_view;
              globalThis.playgroundProbe = { full: 0, views: [] };
              adapter.module._render = (...args) => {
                const result = render(...args);
                if (result === 2) globalThis.playgroundProbe.full++;
                return result;
              };
              adapter.module._set_view = (...args) => {
                globalThis.playgroundProbe.views.push(args);
                return view(...args);
              };
              globalThis.playgroundAdapter = adapter;
              return adapter;
            }`,
              });
            } else {
              try {
                await route.fulfill({ path: target });
              } catch {
                await route.fulfill({
                  status: 404,
                  body: "Missing diagnostic asset",
                });
              }
            }
          });
          const page = await context.newPage();
          const errors = [];
          page.on("pageerror", (error) => errors.push(error.message));
          page.on("console", (message) => {
            if (message.type() === "error") errors.push(message.text());
          });
          await page.goto(
            `http://benchmark.invalid/wasm-playground/${scenario.id}.html?seed=42`,
          );
          await page.waitForFunction(
            () =>
              globalThis.playgroundAdapter || document.querySelector("#error"),
            null,
            { timeout: 30000 },
          );
          assert.equal(
            await page.locator("#error").count(),
            0,
            "page must mount successfully",
          );
          await page.mouse.move(800, 400);
          await page.mouse.down();
          await page.mouse.move(860, 430, { steps: 3 });
          await page.mouse.up();
          await page.waitForFunction(
            () => globalThis.playgroundProbe.full >= 1,
          );
          await page.evaluate(() => {
            globalThis.playgroundProbe.full = 0;
          });
          await page.mouse.wheel(0, -100);
          await page.waitForFunction(
            () => globalThis.playgroundProbe.full >= 1,
          );
          const state = await page.evaluate(() => ({
            views: globalThis.playgroundProbe.views,
            lost: document
              .querySelector("canvas")
              .getContext("webgl2")
              .isContextLost(),
          }));
          assert.equal(state.lost, false);
          assert.ok(
            state.views.some(([scale, x]) => scale === 1 && x > 0),
            "pointer drag must pan",
          );
          assert.ok(
            state.views.some(([scale]) => scale > 1),
            "wheel must zoom",
          );
          assert.deepEqual(errors, []);
          await bounded(
            page.evaluate(() => globalThis.playgroundAdapter.dispose()),
            5000,
          );
          console.log(
            `PASS ${scenario.id}: legacy mount, pan, zoom and disposal (unscored)`,
          );
        })(),
        65000,
      );
    } catch (error) {
      process.exitCode = 1;
      console.error(`FAIL ${scenario.id}: ${error.stack ?? error}`);
    } finally {
      await bounded(context.close(), 5000);
    }
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
