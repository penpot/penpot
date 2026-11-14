import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// `getByText` matches substrings, and the pencil and the curve both name their
// shape "Path", so the row has to be anchored and the row count has to grow.
function layerRow(workspace, name) {
  return workspace.layers
    .getByTestId("layer-row")
    .filter({ hasText: new RegExp(`^${escapeRegExp(name)}$`) })
    .first();
}

// A finished path stays in edition mode, and the path edition bar replaces the
// drawing toolbar while it is active (top_toolbar.cljs:42-47). The step that
// leaves a path behind has to exit it before the next tool is reachable.
async function exitPathEdition(page, workspace) {
  await page.keyboard.press("Escape");
  await expect(workspace.boardButton).toBeVisible();
}

// The text case needs the non-WASM renderer: with the WASM profile a drag with
// the text tool creates no shape at all (the pre-existing text-editor-v2 specs
// fail the same way on this bundle). One page object for the whole document,
// so every step runs under the same renderer.
test("Each toolbar tool creates its shape", async ({ page }) => {
  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();

  const rows = workspace.layers.getByTestId("layer-row");

  await test.step("frame with the toolbar frame tool", async () => {
    const before = await rows.count();

    await workspace.selectToolbarTool(workspace, "Board (B)");
    await workspace.clickWithDragViewportAt(100, 100, 180, 120);

    await expect(layerRow(workspace, "Board")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });

  await test.step("rectangle with the toolbar rect tool", async () => {
    const before = await rows.count();

    await workspace.selectToolbarTool(workspace, "Rectangle (R)");
    await workspace.clickWithDragViewportAt(350, 100, 120, 80);

    await expect(layerRow(workspace, "Rectangle")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });

  await test.step("ellipse from the shapes flyout", async () => {
    const before = await rows.count();

    await workspace.selectToolFromFlyout(workspace, {
      triggerToolName: "Rectangle (R)",
      targetToolName: "Ellipse (E)",
    });
    await workspace.clickWithDragViewportAt(520, 100, 100, 100);

    await expect(layerRow(workspace, "Ellipse")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });

  await test.step("path with the toolbar path tool", async () => {
    const before = await rows.count();

    await workspace.selectToolbarTool(workspace, "Path (P)");
    // The pencil places one node per click and finishes on Enter
    // (data/workspace/path/drawing.cljs:457-477), so a drag leaves a single
    // node behind and the drawing is discarded.
    await workspace.clickAt(120, 320);
    await workspace.clickAt(300, 380);
    await page.keyboard.press("Enter");

    await expect(layerRow(workspace, "Path")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
    await exitPathEdition(page, workspace);
  });

  await test.step("text shape with the toolbar text tool", async () => {
    const before = await rows.count();

    await workspace.selectToolbarTool(workspace, "Text (T)");
    await workspace.clickAndMove(120, 480, 420, 540);
    await workspace.waitForSelectedShapeName("Text");
    await page.keyboard.type("toolbar test");
    await page.keyboard.press("Escape");

    // Typing renames a brand-new text layer after its content.
    await expect(layerRow(workspace, "toolbar test")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });

  // The curve keeps its tool armed after drawing (drawing/common.cljs:138),
  // so it goes last: the next tool click would not take effect.
  await test.step("curve from the path flyout", async () => {
    const before = await rows.count();

    await workspace.selectToolFromFlyout(workspace, {
      triggerToolName: "Path (P)",
      targetToolName: "Curve (Shift+C)",
    });
    await workspace.clickAndMove(520, 320, 620, 380);
    await page.keyboard.press("Enter");

    await expect(layerRow(workspace, "Path")).toBeVisible();
    await expect(rows).toHaveCount(before + 1);
  });
});
