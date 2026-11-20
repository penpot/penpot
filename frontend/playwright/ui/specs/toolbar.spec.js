import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// A finished path stays in edition mode, and the path edition bar replaces the
// drawing toolbar while it is active (`toolbar-hidden-ref` in top_toolbar), so
// the step that leaves a path behind exits it before the next tool is
// reachable.
async function exitPathEdition(page, workspace) {
  await page.keyboard.press("Escape");
  await expect(workspace.boardButton).toBeVisible();
}

// The shape tools run under the WASM renderer: it is the renderer the app
// ships, and the SVG path would let a WASM-only regression in drawing pass.
test.describe("WASM renderer", () => {
  test.beforeEach(async ({ page }) => {
    await WasmWorkspacePage.init(page);
  });

  test("Each toolbar shape tool creates its shape", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const rows = workspace.layers.getByTestId("layer-row");

    await test.step("frame with the toolbar frame tool", async () => {
      const before = await rows.count();

      await workspace.selectToolbarTool(workspace, "Board (B)");
      await workspace.clickWithDragViewportAt(100, 100, 180, 120);

      await expect(workspace.layerRow("Board")).toBeVisible();
      await expect(rows).toHaveCount(before + 1);
    });

    await test.step("rectangle with the toolbar rect tool", async () => {
      const before = await rows.count();

      await workspace.selectToolbarTool(workspace, "Rectangle (R)");
      await workspace.clickWithDragViewportAt(350, 100, 120, 80);

      await expect(workspace.layerRow("Rectangle")).toBeVisible();
      await expect(rows).toHaveCount(before + 1);
    });

    await test.step("ellipse from the shapes flyout", async () => {
      const before = await rows.count();

      await workspace.selectToolFromFlyout(workspace, {
        triggerToolName: "Rectangle (R)",
        targetToolName: "Ellipse (E)",
      });
      await workspace.clickWithDragViewportAt(520, 100, 100, 100);

      await expect(workspace.layerRow("Ellipse")).toBeVisible();
      await expect(rows).toHaveCount(before + 1);
    });

    await test.step("path with the toolbar path tool", async () => {
      const before = await rows.count();

      await workspace.selectToolbarTool(workspace, "Path (P)");
      // Each click places one node (`start-path-from-point` and
      // `make-drag-stream`), and Enter finishes the path through the
      // `:start-editing` binding of the path shortcuts.
      await workspace.clickAt(120, 320);
      await workspace.clickAt(300, 380);
      await page.keyboard.press("Enter");

      await expect(workspace.layerRow("Path")).toBeVisible();
      await expect(rows).toHaveCount(before + 1);
      await exitPathEdition(page, workspace);
    });

    // The curve keeps its tool armed after drawing (`handle-finish-drawing`),
    // so it goes last: the next tool click would not take effect.
    await test.step("curve from the path flyout", async () => {
      const before = await rows.count();
      const pathsBefore = await workspace.layerRows("Path").count();

      await workspace.selectToolFromFlyout(workspace, {
        triggerToolName: "Path (P)",
        targetToolName: "Curve (Shift+C)",
      });
      await workspace.clickAndMove(520, 320, 620, 380);
      await page.keyboard.press("Enter");

      // The path tool's row already exists; the curve must add one more with
      // the same name, so compare against its own baseline.
      await expect(workspace.layerRows("Path")).toHaveCount(pathsBefore + 1);
      await expect(rows).toHaveCount(before + 1);
    });
  });
});

// Text runs under WASM too: `WasmWorkspacePage` enables the v2 text editor,
// and the toolbar gesture (drag, then type) is the same one the SVG run used.
test.describe("Text tool", () => {
  test.beforeEach(async ({ page }) => {
    await WasmWorkspacePage.init(page);
  });

  test("The text tool creates a text layer", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const rows = workspace.layers.getByTestId("layer-row");
    const before = await rows.count();

    await workspace.selectToolbarTool(workspace, "Text (T)");
    await workspace.clickAndMove(120, 480, 420, 540);
    await workspace.waitForSelectedShapeName("Text");
    await page.keyboard.type("toolbar test");
    await page.keyboard.press("Escape");

    // Typing renames a brand-new text layer after its content.
    await expect(workspace.layerRow("toolbar test")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });
});
