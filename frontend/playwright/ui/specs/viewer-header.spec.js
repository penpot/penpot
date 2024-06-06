import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

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
