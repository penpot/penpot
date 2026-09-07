import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("Group bubbles when zooming out if they overlap", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();

  await workspacePage.setupFileWithComments();

  await workspacePage.goToWorkspace();

  await workspacePage.showComments();

  await expect(page.getByTestId("floating-thread-bubble-1")).toBeVisible();
  await expect(page.getByTestId("floating-thread-bubble-2")).toBeVisible();
  await expect(page.getByTestId("floating-thread-bubble-1-2")).toBeHidden();

  const zoom = page.getByTitle("Zoom");
  await zoom.click();

  const zoomOut = page.getByRole("button", { name: "Zoom out" });
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

test("Opening the Comments section only temporarily overrides a disabled global comments setting", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.setupFileWithComments();
  await workspacePage.goToWorkspace();

  const bubble = page.getByTestId("floating-thread-bubble-1");

  // "Display comments" is enabled by default, so the bubble is already visible.
  await expect(bubble).toBeVisible();

  // Turn the global "Display comments" setting off from the main menu.
  await workspacePage.toggleCommentsVisibilityFromMenu();
  await expect(bubble).toBeHidden();

  // Opening the Comments section shows comments regardless of the global setting.
  await workspacePage.showComments();
  await expect(bubble).toBeVisible();

  // Closing the Comments section falls back to the (still disabled) global setting.
  await workspacePage.showComments();
  await expect(bubble).toBeHidden();

  // The global setting itself must be untouched by opening/closing the section.
  await page.getByRole("button", { name: "Main menu" }).click();
  await page.getByText("view").last().click();
  await expect(page.locator("#file-menu-comments")).toContainText(
    "Show comments",
  );
  await page.keyboard.press("Escape");
});

test("Comments stay visible through opening and closing the Comments section when the global setting is enabled", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.setupFileWithComments();
  await workspacePage.goToWorkspace();

  const bubble = page.getByTestId("floating-thread-bubble-1");

  // "Display comments" is enabled by default.
  await expect(bubble).toBeVisible();

  await workspacePage.showComments();
  await expect(bubble).toBeVisible();

  await workspacePage.showComments();
  await expect(bubble).toBeVisible();

  await page.getByRole("button", { name: "Main menu" }).click();
  await page.getByText("view").last().click();
  await expect(page.locator("#file-menu-comments")).toContainText(
    "Hide comments",
  );
  await page.keyboard.press("Escape");
});
