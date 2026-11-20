import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const singleBoardFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const singleBoardPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb2";

// The zoom menu needs a board, so the header test runs on the single-board
// fixture. The logo hit area holds on either fixture.
test("The viewer header keeps its hit area and its zoom menu", async ({
  page,
}) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithSingleBoard();
  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await test.step("the logo hit area ends at the logo", async () => {
    const logoLink = page.getByTestId("penpot-logo-link");
    await expect(logoLink).toBeVisible();

    // Navigating is a potok side effect that lands after the click, so a URL
    // assertion could still match the old URL. Probe the point under the logo
    // instead: a hit area that leaks there would navigate.
    const { x, y } = await logoLink.boundingBox();
    const hitsLogo = await page.evaluate(
      ([px, py]) =>
        !!document
          .elementFromPoint(px, py)
          ?.closest('[data-testid="penpot-logo-link"]'),
      [x, y + 100],
    );
    expect(hitsLogo).toBe(false);
  });

  await test.step("zoom to fit updates the URL", async () => {
    await page.getByTitle("Zoom").click();
    await page.getByText(/Fit/).click();

    await expect(page).toHaveURL(/&zoom=fit/);
  });
});
