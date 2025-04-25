import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(
    page,
    "get-teams",
    "get-teams-render-wasm.json",
  );
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
