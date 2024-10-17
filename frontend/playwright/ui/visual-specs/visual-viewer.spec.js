import { test, expect } from "@playwright/test";
import { ViewerPage } from "../pages/ViewerPage";

test.beforeEach(async ({ page }) => {
  await ViewerPage.init(page);
});

const singleBoardFileId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb1";
const singleBoardPageId = "dd5cc0bb-91ff-81b9-8004-77df9cd3edb2";

test("User goes to an empty Viewer", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupEmptyFile();

  await viewerPage.goToViewer();

  await expect(viewerPage.page.getByTestId("penpot-logo-link")).toBeVisible();
  await expect(viewerPage.page).toHaveScreenshot();
});

test("User goes to the Viewer", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithSingleBoard();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await expect(viewerPage.page.getByTestId("penpot-logo-link")).toBeVisible();
  await expect(viewerPage.page).toHaveScreenshot();
});

test("User goes to the Viewer and opens zoom modal", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithSingleBoard();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.page.getByTitle("Zoom").click();

  await expect(viewerPage.page.getByTestId("penpot-logo-link")).toBeVisible();
  await expect(viewerPage.page).toHaveScreenshot();
});

test("User goes to the Viewer Comments", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithComments();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.showComments();
  await viewerPage.showCommentsThread(1);
  await expect(
    viewerPage.page.getByRole("textbox", { name: "Reply" }),
  ).toBeVisible();

  await expect(viewerPage.page).toHaveScreenshot();
});

test("User opens Viewer comment list", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithComments();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.showComments();
  await viewerPage.page.getByTestId("viewer-comments-dropdown").click();

  await viewerPage.page.getByText("Show comments list").click();

  await expect(
    viewerPage.page.getByRole("button", { name: "Show all comments" }),
  ).toBeVisible();
  await expect(viewerPage.page).toHaveScreenshot();
});

test("User goes to the Viewer Inspect code", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithComments();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.showCode();

  await expect(viewerPage.page.getByText("Size and position")).toBeVisible();

  await expect(viewerPage.page).toHaveScreenshot();
});

test("User goes to the Viewer Inspect code, code tab", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithComments();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.showCode();
  await viewerPage.page.getByRole("tab", { name: "code" }).click();

  await expect(
    viewerPage.page.getByRole("button", { name: "Copy all code" }),
  ).toBeVisible();

  await expect(viewerPage.page).toHaveScreenshot();
});

test("User opens Share modal", async ({ page }) => {
  const viewerPage = new ViewerPage(page);
  await viewerPage.setupLoggedInUser();
  await viewerPage.setupFileWithSingleBoard();

  await viewerPage.goToViewer({
    fileId: singleBoardFileId,
    pageId: singleBoardPageId,
  });

  await viewerPage.page.getByRole("button", { name: "Share" }).click();

  await expect(
    viewerPage.page.getByRole("button", { name: "Get link" }),
  ).toBeVisible();
  await expect(viewerPage.page).toHaveScreenshot();
});
