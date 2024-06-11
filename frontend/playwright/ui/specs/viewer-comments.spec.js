import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const singleBoardFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const singleBoardPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb2";

const setupFileWithSingleBoard = async (viewer) => {
  await viewer.mockRPC(/get\-view\-only\-bundle\?/, "viewer/get-view-only-bundle-single-board.json");
  await viewer.mockRPC("get-comment-threads?file-id=*", "workspace/get-comment-threads-not-empty.json");
  await viewer.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "viewer/get-file-fragment-single-board.json",
  );
  await viewer.mockRPC("get-comments?thread-id=*", "workspace/get-thread-comments.json");
  await viewer.mockRPC("update-comment-thread-status", "workspace/update-comment-thread-status.json");
};

test("Comment is shown with scroll and valid position", async ({ page }) => {
  const viewer = new ViewerPage(page);
  await viewer.setupLoggedInUser();
  await setupFileWithSingleBoard(viewer);

  await viewer.goToViewer({ fileId: singleBoardFileId, pageId: singleBoardPageId });
  await viewer.showComments();
  await viewer.showCommentsThread(1);
  await expect(viewer.page.getByRole("textbox", { name: "Reply" })).toBeVisible();
  await viewer.showCommentsThread(1);
  await viewer.showCommentsThread(2);
  await expect(viewer.page.getByRole("textbox", { name: "Reply" })).toBeVisible();
});
