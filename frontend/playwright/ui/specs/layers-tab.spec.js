import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 7466 - Layers tab height extends to the bottom when 'Pages' is collapsed", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();

  await workspace.goToWorkspace();

  const { height: heightExpanded } = await workspace.layers.boundingBox();
  await workspace.togglePages();
  const { height: heightCollapsed } = await workspace.layers.boundingBox();

  expect(heightExpanded > heightCollapsed);
});
