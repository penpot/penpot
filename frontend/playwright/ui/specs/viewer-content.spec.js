import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const multipleBoardsFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb0";
const multipleBoardsPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb3";

test("Navigate with arrows", async ({ page }) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithMultipleBoards(viewer);

  await viewer.goToViewer({
    fileId: multipleBoardsFileId,
    pageId: multipleBoardsPageId,
  });

  const nextButton = viewer.page.getByRole("button", {
    name: "Next",
  });
  await nextButton.click();
  await expect(viewer.page).toHaveURL(/&index=1/);
});
