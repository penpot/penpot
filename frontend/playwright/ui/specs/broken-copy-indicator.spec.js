import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// The file has three component copies: one whose main is still on the
// page, one whose main was deleted, and one that points at a file the
// current file does not use.
const fileId = "0810e161-5477-81e1-8008-d94c91d8ab73";
const pageId = "0810e161-5477-81e1-8008-d94c91d8ab74";

const brokenIndicator = (workspacePage) =>
  workspacePage.rightSidebar.getByLabel(
    "Main component not found. This copy won’t receive updates.",
  );

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-broken-copies.json",
  );
  await workspacePage.goToWorkspace({ fileId, pageId });
});

test("Shows the broken indicator on a copy whose main was deleted", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.clickLeafLayer("Deleted copy");

  await expect(brokenIndicator(workspacePage)).toBeVisible();
});

test("Shows the broken indicator on a copy from a file not in use", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.clickLeafLayer("Orphan copy");

  await expect(brokenIndicator(workspacePage)).toBeVisible();
});

test("Shows the tooltip when hovering the broken indicator", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.clickLeafLayer("Orphan copy");

  await brokenIndicator(workspacePage).hover();

  await expect(page.getByRole("tooltip")).toHaveText(
    "Main component not found. This copy won’t receive updates.",
  );
});

test("Shows no broken indicator on a copy whose main exists", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.clickLeafLayer("Healthy copy");

  await expect(
    workspacePage.rightSidebar.getByTestId("component-pill-button"),
  ).toBeVisible();
  await expect(brokenIndicator(workspacePage)).toHaveCount(0);
});

test("Shows the broken indicator when every selected copy is broken", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.clickLeafLayer("Deleted copy");
  await workspacePage.clickLeafLayer("Orphan copy", { modifiers: ["Shift"] });

  await expect(brokenIndicator(workspacePage)).toBeVisible();
});
