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

test("Stroke to path: rectangle with center stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});

test("Stroke to path: rectangle with inner stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Inside" }).click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});

test("Stroke to path: rectangle with outer stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Outside" }).click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});

test("Stroke to path: circle with center stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.ellipseShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 200);
  await workspace.waitForSelectedShapeName("Ellipse");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Ellipse", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Ellipse (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Ellipse").first()).toBeVisible();
});

test("Stroke to path: circle with inner stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.ellipseShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 200);
  await workspace.waitForSelectedShapeName("Ellipse");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Inside" }).click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Ellipse", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Ellipse (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Ellipse").first()).toBeVisible();
});

test("Stroke to path: circle with outer stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.ellipseShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 200);
  await workspace.waitForSelectedShapeName("Ellipse");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Outside" }).click();

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Ellipse", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Ellipse (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Ellipse").first()).toBeVisible();
});

test("Stroke to path: path with center stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  // Flatten to path
  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Flatten").click();
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});

test("Stroke to path: path with inner stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Flatten").click();
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Inside" }).click();

  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});

test("Stroke to path: path with outer stroke", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  await workspace.rectShapeButton.click();
  await workspace.clickWithDragViewportAt(200, 200, 200, 150);
  await workspace.waitForSelectedShapeName("Rectangle");

  await workspace.clickLayers();
  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Flatten").click();
  await workspace.waitForSelectedShapeName("Rectangle");

  const rightSidebar = page.getByTestId("right-sidebar");
  await rightSidebar.getByTestId("add-stroke").click();

  const alignmentSelect = rightSidebar
    .getByTestId("stroke.alignment")
    .getByRole("combobox");
  await alignmentSelect.click();
  await page.getByRole("option", { name: "Outside" }).click();

  await workspace.clickLeafLayer("Rectangle", { button: "right" });
  await page.getByText("Stroke to path").click();

  await expect(workspace.layers.getByText("Rectangle (stroke)")).toBeVisible();
  await expect(workspace.layers.getByText("Rectangle").first()).toBeVisible();
});
