import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import fs from "fs/promises";

const BENCHMARK_FILE = "benchmark_results.json";

test.describe("Test current render-wasm", async () => {
  test.beforeEach(async ({ page }) => {
    await WorkspacePage.init(page);
    await WorkspacePage.mockRPC(page, "get-teams", "use-render-wasm.json");
  });

  test("Check performance", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile(page);
    await workspacePage.goToWorkspace();

    await workspacePage.page.exposeFunction("drawRect", async () => {
      await workspacePage.rectShapeButton.click();
      await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);
    });

    const newResults = await workspacePage.page.evaluate(async () => {
      const start = performance.now();
      await window.drawRect();
      const end = performance.now();
      const results = { renderTime: end - start };
      return results;
    });

    let prevResults = null;

    try {
      await fs.access(BENCHMARK_FILE);
      const data = await fs.readFile(BENCHMARK_FILE, "utf8");
      prevResults = JSON.parse(data);
    } catch (err) {
      console.log("No previous benchmark results found.");
    }

    if (prevResults) {
      const renderDiff = newResults.renderTime - prevResults.renderTime;
      expect(renderDiff).toBeLessThanOrEqual(5);
    } else {
      await fs.writeFile(BENCHMARK_FILE, JSON.stringify(newResults, null, 2));
    }
  });
});

test.describe("Compare render-wasm", async () => {
  test.beforeEach(async ({ page }) => {
    await WorkspacePage.init(page);
  });

  test("Compare performance", async ({ page }) => {
    const workspacePageOldRender = new WorkspacePage(page);
    await workspacePageOldRender.setupEmptyFile(page);
    await workspacePageOldRender.goToWorkspace();

    await workspacePageOldRender.page.exposeFunction(
      "drawRectOldRender",
      async () => {
        await workspacePageOldRender.rectShapeButton.click();
        await workspacePageOldRender.clickWithDragViewportAt(
          128,
          128,
          200,
          100,
        );
      },
    );

    const resultsOldRender = await workspacePageOldRender.page.evaluate(
      async () => {
        const start = performance.now();
        await window.drawRectOldRender();
        const end = performance.now();
        const results = { renderTime: end - start };
        return results;
      },
    );

    await WorkspacePage.mockRPC(page, "get-teams", "use-render-wasm.json");
    const workspacePageNewRender = new WorkspacePage(page);
    await workspacePageNewRender.setupEmptyFile(page);
    await workspacePageNewRender.goToWorkspace();

    await workspacePageNewRender.page.exposeFunction(
      "drawRectNewRender",
      async () => {
        await workspacePageNewRender.rectShapeButton.click();
        await workspacePageNewRender.clickWithDragViewportAt(
          128,
          128,
          200,
          100,
        );
      },
    );

    const resultsNewRender = await workspacePageNewRender.page.evaluate(
      async () => {
        const start = performance.now();
        await window.drawRectNewRender();
        const end = performance.now();
        const results = { renderTime: end - start };
        return results;
      },
    );

    // FIXME
    const renderDiff = Math.abs(
      resultsOldRender.renderTime - resultsNewRender.renderTime,
    );
    expect(renderDiff).toBeLessThanOrEqual(200);
  });
});
