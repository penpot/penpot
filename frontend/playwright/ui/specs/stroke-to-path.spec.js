import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
    "enable-stroke-path",
  ]);
});

// Every one of the nine shapes in the fixture carries a stroke, one per
// geometry (rectangle, ellipse, path) and alignment (center, inner, outer).
async function setupShapesWithStrokes(page) {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("workspace/shapes-with-strokes.json");
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();
  return workspace;
}

async function strokeToPath(page, workspace, shapeName, strokeName) {
  await expect(workspace.layerRow(shapeName)).toBeVisible();
  const rowsBefore = await workspace.layers.getByTestId("layer-row").count();

  // The menu entry converts the current selection, and
  // `show-shape-context-menu` selects the shape before opening the menu. The
  // selection assertion attributes the conversion to this row instead of a
  // stale selection or a "(stroke)" child.
  await workspace.selectLayer(shapeName, { button: "right" });

  await page.getByText("Stroke to path").click();

  await expect(workspace.layerRow(strokeName)).toBeVisible();
  await expect(workspace.layerRow(shapeName)).toBeVisible();
  await expect(workspace.layers.getByTestId("layer-row")).toHaveCount(
    rowsBefore + 1,
  );
  await expect(page.getByTestId("context-menu")).toBeHidden();
}

test("Stroke to path converts each stroked shape into a path", async ({
  page,
}) => {
  const workspace = await setupShapesWithStrokes(page);
  await workspace.clickLayers();

  await test.step("rectangle with center stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "rectangle-center",
      "rectangle-center (stroke)",
    );
  });

  await test.step("rectangle with inner stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "rectangle-inner",
      "rectangle-inner (stroke)",
    );
  });

  await test.step("rectangle with outer stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "rectangle-outer",
      "rectangle-outer (stroke)",
    );
  });

  await test.step("circle with center stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "ellipse-center",
      "ellipse-center (stroke)",
    );
  });

  await test.step("circle with inner stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "ellipse-inner",
      "ellipse-inner (stroke)",
    );
  });

  await test.step("circle with outer stroke", async () => {
    await strokeToPath(
      page,
      workspace,
      "ellipse-outer",
      "ellipse-outer (stroke)",
    );
  });

  await test.step("path with center stroke", async () => {
    await strokeToPath(page, workspace, "path-center", "path-center (stroke)");
  });

  await test.step("path with inner stroke", async () => {
    await strokeToPath(page, workspace, "path-inner", "path-inner (stroke)");
  });

  await test.step("path with outer stroke", async () => {
    await strokeToPath(page, workspace, "path-outer", "path-outer (stroke)");
  });
});
