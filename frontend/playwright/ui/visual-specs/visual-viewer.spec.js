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
    viewerPage.page.getByRole("button", { name: "Post" }),
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

  await expect(
    viewerPage.page.getByRole("button", {
      name: "Toggle panel Size and position",
    }),
  ).toBeVisible();

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
  await viewerPage.page
    .getByRole("combobox")
    .filter({ hasText: "Styles" })
    .click();
  await viewerPage.page.getByRole("option", { name: "Code" }).click();

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

test.describe("Thumbnails panel", () => {
  test("Shows the thumbnails panel collapsed and expanded", async ({
    page,
  }) => {
    const viewerPage = new ViewerPage(page);
    await viewerPage.setupLoggedInUser();
    await viewerPage.setupFileWithMultipleBoards();
    await viewerPage.goToViewer({
      fileId: "dd5cc0bb-91ff-81b9-8004-77df9cd3edb0",
      pageId: "dd5cc0bb-91ff-81b9-8004-77df9cd3edb3",
    });

    await page.locator("#current-frame").click();
    const panel = page.locator("section").filter({ hasText: /boards/i });
    await expect(panel).toBeVisible();
    await page.mouse.move(640, 600);
    // Collapsed: the summary with its expand and close buttons.
    await expect(panel).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    // The scroll handlers only show on hover.
    await panel.locator("button").nth(3).hover();
    await expect(panel).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await panel.locator("button").first().click();
    await page.mouse.move(640, 700);
    // Expanded: the expand arrow points up.
    await expect(panel).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Comments filter", () => {
  test("Shows the comments filter closed and open", async ({ page }) => {
    const viewerPage = new ViewerPage(page);
    await viewerPage.setupLoggedInUser();
    await viewerPage.setupFileWithComments();
    await viewerPage.goToViewer({
      fileId: singleBoardFileId,
      pageId: singleBoardPageId,
    });
    await viewerPage.showComments();

    const trigger = page.getByTestId("viewer-comments-dropdown");
    await expect(trigger).toBeVisible();
    await page.mouse.move(640, 600);
    // Closed: the "Comments" trigger with its arrow.
    await expect(trigger).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await trigger.click();
    await expect(page.getByText("Show comments list")).toBeVisible();
    await page.mouse.move(640, 600);
    // Open: the filter menu with the selected option ticked.
    const menu = page.getByText("Show comments list").locator("../..");
    await expect(menu).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Board navigation", () => {
  test("Shows the previous, next and first board buttons", async ({ page }) => {
    const viewerPage = new ViewerPage(page);
    await viewerPage.setupLoggedInUser();
    await viewerPage.setupFileWithMultipleBoards();
    await viewerPage.goToViewer({
      fileId: "dd5cc0bb-91ff-81b9-8004-77df9cd3edb0",
      pageId: "dd5cc0bb-91ff-81b9-8004-77df9cd3edb3",
    });

    const next = page.getByRole("button", { name: "Next" });
    await expect(next).toBeVisible();
    await page.mouse.move(640, 300);
    // Wait for the slide-in transition of the controls.
    await page.waitForTimeout(800);
    // First board: the next and first board buttons.
    await expect(page).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await next.hover();
    await expect(next).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await next.click();
    const previous = page.getByRole("button", { name: "Previous" });
    await previous.hover();
    await expect(previous).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    const first = page.locator("[class*='viewer-bottom'] button");
    await first.hover();
    await expect(first).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});
