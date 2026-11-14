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

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

// `getByText` matches substrings and the conversion names the derived path
// "<shape> (stroke)", so an unanchored lookup can click the child and then
// assert a row that already existed. The anchored match assumes the row shows
// only the layer name: with the debug flags on, the row appends text to the
// same node (layer_name.cljs:138-146).
function layerRow(workspace, name) {
  return workspace.layers
    .getByTestId("layer-row")
    .filter({ hasText: new RegExp(`^${escapeRegExp(name)}$`) })
    .first();
}

async function strokeToPath(page, workspace, shapeName, strokeName) {
  const row = layerRow(workspace, shapeName);
  await expect(row).toBeVisible();
  const rowsBefore = await workspace.layers.getByTestId("layer-row").count();

  await row.click({ button: "right" });
  // The menu entry converts the current selection, and a right click selects
  // the shape before the menu opens (data/workspace.cljs:1299-1302). The
  // guard is what attributes the conversion to this row instead of a stale
  // selection or a "(stroke)" child.
  await expect(row).toHaveAttribute("aria-checked", "true");

  await page.getByText("Stroke to path").click();

  await expect(layerRow(workspace, strokeName)).toBeVisible();
  await expect(layerRow(workspace, shapeName)).toBeVisible();
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
