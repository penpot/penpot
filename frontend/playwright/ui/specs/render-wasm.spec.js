import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, [
    "enable-feature-render-wasm",
    "enable-render-wasm-dpr",
  ]);
});

test("BUG 10867 - Crash when loading comments", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.showComments();
  await expect(
    workspacePage.rightSidebar.getByText("Show all comments"),
  ).toBeVisible();
});
