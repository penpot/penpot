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

async function setupShapesWithStrokes(page) {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("workspace/shapes-with-strokes.json");
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/shapes-with-strokes.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();
  return workspace;
}

async function strokeToPath(workspace, page, shapeName, expectedStrokeName) {
  await workspace.clickLayers();
  await workspace.clickLeafLayer(shapeName, { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(
    workspace.layers.getByText(expectedStrokeName),
  ).toBeVisible();
  await expect(workspace.layers.getByText(shapeName).first()).toBeVisible();
}

test("Stroke to path: rectangle with center stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "rectangle-center", "rectangle-center (stroke)");
});

test("Stroke to path: rectangle with inner stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "rectangle-inner", "rectangle-inner (stroke)");
});

test("Stroke to path: rectangle with outer stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "rectangle-outer", "rectangle-outer (stroke)");
});

test("Stroke to path: circle with center stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "ellipse-center", "ellipse-center (stroke)");
});

test("Stroke to path: circle with inner stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "ellipse-inner", "ellipse-inner (stroke)");
});

test("Stroke to path: circle with outer stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "ellipse-outer", "ellipse-outer (stroke)");
});

test("Stroke to path: path with center stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "path-center", "path-center (stroke)");
});

test("Stroke to path: path with inner stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "path-inner", "path-inner (stroke)");
});

test("Stroke to path: path with outer stroke", async ({ page }) => {
  const workspace = await setupShapesWithStrokes(page);
  await strokeToPath(workspace, page, "path-outer", "path-outer (stroke)");
});
