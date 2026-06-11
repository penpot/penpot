import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const multipleBoardsFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb0";
const multipleBoardsPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb3";

test("[View mode] Export presets are preserved when navigating between boards in inspect mode", async ({
  page,
}) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithMultipleBoards();

  await viewer.goToViewer({
    fileId: multipleBoardsFileId,
    pageId: multipleBoardsPageId,
  });

  // Enter inspect (code) mode
  await viewer.showCode();

  // Wait for the inspect panel to load
  await page.waitForSelector(".main_ui_inspect_exports__add-export");

  // Add an export preset via the "+" button in the Export section
  const addExportButton = page.locator(".main_ui_inspect_exports__add-export");
  await addExportButton.click();

  // Verify the "Export 1 element" button appears, confirming the preset was added
  const exportButton = page.getByRole("button", { name: "Export 1 element" });
  await expect(exportButton).toBeVisible();

  // Navigate to another board
  const nextButton = page.getByRole("button", { name: "Next" });
  await nextButton.click();
  await expect(page).toHaveURL(/&index=1/);

  // Navigate back to the first board
  const prevButton = page.locator(".main_ui_viewer__viewer-go-prev");
  await prevButton.click();
  await expect(page).toHaveURL(/&index=0/);

  // Export preset should still be visible after returning to the first board
  await expect(exportButton).toBeVisible();
});
