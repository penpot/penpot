import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 13958 - Fill token gets detached when editing text shape", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  // Load a file that has a text shape, whose content contains a `:fills []` in the root node.
  // This attribute is removed when editing the text, causing the old code to detect that the
  // fills had changed and detaching the token.
  await workspacePage.mockGetFile("workspace/get-file-13958.json");
  await workspacePage.goToWorkspace();

  // Check token is attached to the shape
  await workspacePage.clickLeafLayer("Design tokens are a set");
  await expect(workspacePage.rightSidebar.getByLabel("xx.alias.color.text.default", { exact: true })).toBeVisible();

  // Enter and exit the text editor
  await workspacePage.page.keyboard.press("Enter");
  await expect(workspacePage.page.getByTestId("text-editor")).toBeVisible();
  await workspacePage.page.keyboard.press("Escape");
  await expect(workspacePage.page.getByTestId("text-editor")).not.toBeAttached();

  // Assert token is still attached to the shape
  await expect(workspacePage.rightSidebar.getByLabel("xx.alias.color.text.default", { exact: true })).toBeVisible();
});
