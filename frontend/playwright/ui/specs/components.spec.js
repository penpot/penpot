import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 13267 - Component instance is not synced with parent for geometry changes", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockGetFile("components/get-file-13267.json");

  await workspacePage.goToWorkspace({
    fileId: "e9c84e12-dd29-80fc-8007-86d559dced7f",
    pageId: "e9c84e12-dd29-80fc-8007-86d559dced80",
  });

  // Create a component instance
  await workspacePage.clickLeafLayer("A Component");
  await workspacePage.page.keyboard.press("ControlOrMeta+d");

  // Select the main component
  await workspacePage.clickLeafLayer("A Component", {}, 1);
  const rotationInput = workspacePage.rightSidebar.getByTestId("rotation").getByRole("textbox");
  await rotationInput.fill("45");
  await rotationInput.press("Enter");

  // Select the instance rect
  await workspacePage.clickToggableLayer("A Component", {}, 0);
  await workspacePage.clickLeafLayer("Rectangle");

  await expect(rotationInput).toHaveValue("45");
});