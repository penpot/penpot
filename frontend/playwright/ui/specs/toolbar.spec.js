import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";
import WorkspacePage from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

const expectLayerNamed = async (workspacePage, name) => {
  await expect(workspacePage.layers.getByText(name).last()).toBeVisible();
};

test("User creates a frame with the toolbar frame tool", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolbarTool(workspacePage, "Board (B)");
  await workspacePage.clickWithDragViewportAt(100, 100, 180, 120);
  await expectLayerNamed(workspacePage, "Board");
});

test("User creates a rectangle with the toolbar rect tool", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolbarTool(workspacePage, "Rectangle (R)");
  await workspacePage.clickWithDragViewportAt(350, 100, 120, 80);
  await expectLayerNamed(workspacePage, "Rectangle");
});

test("User creates an ellipse from the shapes flyout", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolFromFlyout(workspacePage, {
    triggerToolName: "Rectangle (R)",
    targetToolName: "Ellipse (E)",
  });
  await workspacePage.clickWithDragViewportAt(520, 100, 100, 100);
  await expectLayerNamed(workspacePage, "Ellipse");
});

test("User creates a text shape with the toolbar text tool", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolbarTool(workspacePage, "Text (T)");
  await workspacePage.clickAndMove(120, 320, 300, 380);
  await workspacePage.waitForSelectedShapeName("Text");
  await workspacePage.page.keyboard.type("toolbar test");
  await workspacePage.page.keyboard.press("Escape");
  await expectLayerNamed(workspacePage, "Text");
});

test.skip("User creates a path with the toolbar path tool", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolbarTool(workspacePage, "Path (P)");
  await workspacePage.clickAndMove(120, 320, 300, 380);
  await workspacePage.page.keyboard.press("Enter");
  await expectLayerNamed(workspacePage, "Path");
});

test("User creates a curve from the path flyout", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.selectToolFromFlyout(workspacePage, {
    triggerToolName: "Path (P)",
    targetToolName: "Curve (Shift+C)",
  });
  await workspacePage.clickAndMove(120, 320, 300, 380);
  await workspacePage.page.keyboard.press("Enter");
  await expectLayerNamed(workspacePage, "Path");
});
