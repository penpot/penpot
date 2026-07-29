import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// UUIDs matching the fixture file `workspace/get-file-rename-page.json`
const FILE_ID = "aaaaaaaa-0000-0000-0000-000000000001";
const PAGE1_ID = "bbbbbbbb-0000-0000-0000-000000000001"; // non-empty (has a rectangle)
const PAGE2_ID = "cccccccc-0000-0000-0000-000000000002"; // empty
const PAGE3_ID = "dddddddd-0000-0000-0000-000000000003"; // empty

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

/**
 * Returns a locator for the interactive body of a page item in the sitemap
 * sidebar, identified by its page UUID.
 */
function getPageItem(page, pageId) {
  return page.getByTestId(`page-${pageId}`);
}

/**
 * Returns the visible name span inside a page item (not in edit mode).
 */
function getPageNameSpan(page, pageId) {
  return getPageItem(page, pageId).getByTestId("page-name");
}

/**
 * Double-clicks a page item to enter rename mode, types the new name and
 * confirms with Enter.
 */
async function renamePage(page, pageId, newName) {
  const item = getPageItem(page, pageId);
  await item.dblclick();
  const input = item.locator("input");
  await expect(input).toBeVisible();
  await input.selectText();
  await input.fill(newName);
  await page.keyboard.press("Enter");
}

async function setupWorkspace(workspacePage) {
  await workspacePage.setupEmptyFile();

  // Override the file mock with the 3-page fixture.
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-rename-page.json",
  );

  // Mock update-file so rename commits don't fail.
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  // The base WorkspacePage uses `this.pageName` (getByTestId("page-name")) as a
  // readiness signal inside goToWorkspace. With 3 pages in the sidebar there are
  // 3 matching elements, which violates Playwright's strict mode. Narrow the
  // locator to the first occurrence so the internal readiness check can pass.
  workspacePage.pageName = workspacePage.page
    .getByTestId("page-name")
    .first();

  await workspacePage.goToWorkspace({
    fileId: FILE_ID,
    pageId: PAGE1_ID,
    pageName: "Page 1",
  });
}

test("User renames a non-empty page to '---' — page is renamed, URL does not change", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupWorkspace(workspacePage);

  // Confirm we are on Page 1.
  await expect(getPageNameSpan(page, PAGE1_ID)).toHaveText("Page 1");

  const urlBefore = page.url();
  expect(urlBefore).toContain(`page-id=${PAGE1_ID}`);

  // Rename the non-empty page to "---".
  await renamePage(page, PAGE1_ID, "---");

  // The page name should have changed to "---".
  await expect(getPageNameSpan(page, PAGE1_ID)).toHaveText("---");

  // The URL must still point to the same page (no navigation triggered).
  await expect(page).toHaveURL(new RegExp(`page-id=${PAGE1_ID}`));

  // The page must NOT be rendered as a separator (it still has shapes).
  await expect(
    getPageItem(page, PAGE1_ID).getByTestId("page-separator"),
  ).not.toBeVisible();
});

test("User renames an empty page to '---' — page becomes a separator and URL changes", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupWorkspace(workspacePage);

  // Navigate to the second page (empty) by clicking it in the sitemap.
  const page2Item = getPageItem(page, PAGE2_ID);
  await page2Item.click();

  // Wait until the URL reflects the navigation to Page 2.
  await expect(page).toHaveURL(new RegExp(`page-id=${PAGE2_ID}`));

  // Rename the empty page to "---".
  await renamePage(page, PAGE2_ID, "---");

  // Since the renamed page is empty, it must now be shown as a separator.
  await expect(
    getPageItem(page, PAGE2_ID).getByTestId("page-separator"),
  ).toBeVisible();

  // The application must have navigated away from the separator page to
  // a different page (Page 1 or Page 3 depending on fallback logic).
  await expect(page).not.toHaveURL(new RegExp(`page-id=${PAGE2_ID}`));

  // The current URL must still be a workspace URL pointing to the same file.
  await expect(page).toHaveURL(new RegExp(`file-id=${FILE_ID}`));
});
