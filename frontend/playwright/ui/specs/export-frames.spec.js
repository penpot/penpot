import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

/**
 * Helper function to setup workspace with frames for export testing
 */
async function setupWorkspaceWithFrames(workspacePage) {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-export-frames.json",
  );
  await workspacePage.mockRPC(
    "get-file-object-thumbnails?file-id=*",
    "workspace/get-file-object-thumbnails-blank.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*",
    "workspace/get-file-fragment-blank.json",
  );
  await workspacePage.goToWorkspace({
    fileId: "8d102c33-b98f-81f6-8006-fdd4ea9780d7",
    pageId: "8d102c33-b98f-81f6-8006-fdd4ea9780d8",
  });
}

test.describe("Export frames to PDF", () => {
  test("Export frames menu option is NOT visible when page has no frames", async ({
    page,
  }) => {
    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await workspacePage.goToWorkspace();

    // Open main menu
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();

    // The "Export frames to PDF" option should NOT be visible when there are no frames
    await expect(page.locator("#file-menu-export-frames")).not.toBeVisible();
  });

  test("Export frames menu option is visible when there are frames (even if not selected)", async ({
    page,
  }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Open main menu
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();

    // The "Export frames to PDF" option should be visible when there are frames on the page
    await expect(page.locator("#file-menu-export-frames")).toBeVisible();
  });

  test("Export frames modal shows all frames when none are selected", async ({
    page,
  }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Don't select any frame

    // Open main menu
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();

    // Click on "Export frames to PDF"
    await page.locator("#file-menu-export-frames").click();

    // The modal title should be correct
    await expect(page.getByText("Export as PDF")).toBeVisible();

    // Both frames should appear in the list
    await expect(page.getByRole("button", { name: "Board 1" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Board 2" })).toBeVisible();

    // The selection counter should show "2 of 2"
    await expect(page.getByText("2 of 2 elements selected")).toBeVisible();
  });

  test("Export frames modal shows only the selected frames", async ({
    page,
  }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Select Frame 1
    await workspacePage.clickLeafLayer("Board 1");

    // Open main menu
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();

    // Click on "Export frames to PDF"
    await page.locator("#file-menu-export-frames").click();

    // The modal title should be correct
    await expect(page.getByText("Export as PDF")).toBeVisible();

    // Only Frame 1 should appear in the list
    // await page.getByRole("button", { name: "Board 1" }),
    await expect(page.getByRole("button", { name: "Board 1" })).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Board 2" }),
    ).not.toBeVisible();

    // The selection counter should show "1 of 1"
    await expect(page.getByText("1 of 1 elements selected")).toBeVisible();
  });

  test("User can deselect frames in the export modal", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Select Frame 1
    await workspacePage.clickLeafLayer("Board 1");

    // Add Frame 2 to selection
    await page.keyboard.down("Shift");
    await workspacePage.clickLeafLayer("Board 2");
    await page.keyboard.up("Shift");

    // Open main menu and click export
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();
    await page.locator("#file-menu-export-frames").click();

    // The modal should appear with 2 frames
    await expect(page.getByText("2 of 2 elements selected")).toBeVisible();

    // Click on the checkbox next to Frame 1 to deselect it
    await page.getByRole("button", { name: "Board 1" }).click();

    // Now only 1 frame should be selected
    await expect(page.getByText("1 of 2 elements selected")).toBeVisible();

    // The export button should still be enabled
    const exportButton = page.locator("input[type='button'][value='Export']");
    await expect(exportButton).toBeEnabled();
  });

  test("Export button is disabled when all frames are deselected", async ({
    page,
  }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Select Frame 1
    await workspacePage.clickLeafLayer("Board 1");

    // Open main menu and click export
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();
    await page.locator("#file-menu-export-frames").click();

    // Deselect the frame
    await page.getByRole("button", { name: "Board 1" }).click();

    // Now 0 frames are selected
    await expect(page.getByText("0 of 1 elements selected")).toBeVisible();

    // // The export button should be disabled
    await expect(
      page.getByRole("button", { name: "Export", exact: true }),
    ).toBeDisabled();
  });

  test("User can cancel the export modal", async ({ page }) => {
    const workspacePage = new WorkspacePage(page);
    await setupWorkspaceWithFrames(workspacePage);

    // Select Frame 1
    await workspacePage.clickLeafLayer("Board 1");

    // Open main menu and click export
    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();
    await page.locator("#file-menu-export-frames").click();

    // Now only 1 frame should be selected
    await expect(page.getByText("1 of 1 elements selected")).toBeVisible();

    // Click cancel
    await page.getByRole("button", { name: "Cancel" }).click();

    // The modal should be hidden
    await expect(page.getByText("0 of 1 elements selected")).not.toBeVisible();
  });
});
