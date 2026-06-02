import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const singleBoardFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const singleBoardPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb2";

test("Clips link area of the logo", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupEmptyFile();

  await viewerPage.goToViewer();

  const viewerUrl = page.url();

  const logoLink = viewerPage.page.getByTestId("penpot-logo-link");
  await expect(logoLink).toBeVisible();

  const { x, y } = await logoLink.boundingBox();
  await viewerPage.page.mouse.click(x, y + 100);
  await expect(page.url()).toBe(viewerUrl);
});

test("Updates URL with zoom type", async ({ page }) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithSingleBoard(viewer);

  await viewer.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewer.page.getByTitle("Zoom").click();
  await viewer.page.getByText(/Fit/).click();

  await expect(viewer.page).toHaveURL(/&zoom=fit/);
});
