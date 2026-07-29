import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const singleBoardFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const singleBoardPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb2";

test("Comment is shown with scroll and valid position", async ({ page }) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await viewer.setupFileWithComments();

  await viewer.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });
  await viewer.showComments();
  await viewer.showCommentsThread(1);
  await expect(viewer.page.getByRole("textbox")).toBeVisible();
  await viewer.showCommentsThread(1);
  await viewer.showCommentsThread(2);
  await expect(viewer.page.getByRole("textbox")).toBeVisible();
});
