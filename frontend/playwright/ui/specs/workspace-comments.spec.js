import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test("Group bubbles when zooming out if they overlap", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();

  await workspacePage.setupFileWithComments();

  await workspacePage.goToWorkspace();

  await workspacePage.showComments();

  await expect(page.getByTestId("floating-thread-bubble-1")).toBeVisible();
  await expect(page.getByTestId("floating-thread-bubble-2")).toBeVisible();
  await expect(page.getByTestId("floating-thread-bubble-1-2")).toBeHidden();

  const zoom = page.getByTitle("Zoom");
  await zoom.click();

  const zoomOut = page.getByTitle("Zoom out");
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();
  await zoomOut.click();

  await expect(page.getByTestId("floating-thread-bubble-1")).toBeHidden();
  await expect(page.getByTestId("floating-thread-bubble-2")).toBeHidden();
  await expect(page.getByTestId("floating-thread-bubble-1-2")).toBeVisible();
  await expect(page.getByTestId("floating-thread-bubble-1-2")).toHaveClass(
    /unread/,
  );
});
