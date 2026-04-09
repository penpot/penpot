import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 13958 - Fill token gets detached when editing text shape", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  // NOTE: this file has been created with the old renderer
  await workspacePage.mockGetFile("workspace/get-file-13958.json");
  await workspacePage.goToWorkspace();

  // Check token is attached to the shape
  await workspacePage.clickLeafLayer("Hola Caracola");
  await expect(workspacePage.rightSidebar.getByLabel("text-color", { exact: true })).toBeVisible();

  // Enter and exit the text editor
  await workspacePage.page.keyboard.press("Enter");
  await expect(workspacePage.page.getByTestId("text-editor")).toBeVisible();
  await workspacePage.page.keyboard.press("Escape");
  await expect(workspacePage.page.getByTestId("text-editor")).not.toBeAttached();

  // Assert token is still attached to the shape
  await expect(workspacePage.rightSidebar.getByLabel("text-color", { exact: true })).toBeVisible();
});
