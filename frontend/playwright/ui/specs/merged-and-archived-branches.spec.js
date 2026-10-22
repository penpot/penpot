import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

const setupBranchFile = async (workspacePage, branchInfo) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*": `pull-requests/${branchInfo}`,
    "get-file-branches?file-id=*":
      "pull-requests/get-file-branches-closed.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-branches-empty.json",
    "get-branch-diff?branch-id=*": "pull-requests/get-branch-diff-empty.json",
  });
};

test("a kept merged branch opens read-only", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-merged.json");
  await workspacePage.goToWorkspace();

  const banner = page.locator("#branch-banner");
  await expect(banner.getByText("Merged branch")).toBeVisible();
  await expect(
    banner.getByRole("button", { name: "View in main" }),
  ).toBeVisible();
  await expect(banner.getByRole("button", { name: "Compare" })).toBeHidden();
  await expect(banner.getByRole("button", { name: "Restore" })).toBeHidden();

  await expect(page.getByText("Merged into main, read only")).toBeVisible();
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeHidden();
});

test("an archived branch offers Restore and Compare", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-archived.json");
  await workspacePage.mockRPC(
    "archive-file-branch",
    "pull-requests/archive-file-branch-restore.json",
  );
  await workspacePage.goToWorkspace();

  const banner = page.locator("#branch-banner");
  await expect(banner.getByText("Archived branch")).toBeVisible();
  await expect(banner.getByText("Merged branch")).toBeHidden();
  await expect(banner.getByRole("button", { name: "Compare" })).toBeVisible();
  await expect(page.getByText("Archived, not merged")).toBeVisible();

  // an archived branch keeps its work and stays editable
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeVisible();

  // restoring reopens the branch, and the banner follows its new status
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "pull-requests/get-file-branch-info-branch.json",
  );
  const restore = page.waitForRequest(
    (req) =>
      req.url().includes("archive-file-branch") &&
      /"~:archived":\s*false/.test(req.postData() ?? ""),
  );
  await banner.getByRole("button", { name: "Restore" }).click();
  await restore;
  await expect(banner.getByText("1 change not in main")).toBeVisible();
});

test("the switcher groups merged and archived branches apart", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-none.json");
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  const popover = page.getByRole("dialog", { name: "Branches" });

  await popover.getByRole("button", { name: /^Merged/ }).click();
  await expect(popover.getByText("shipped")).toBeVisible();
  await expect(popover.getByText("parked")).toBeHidden();

  await popover.getByRole("button", { name: /^Archived/ }).click();
  await expect(popover.getByText("parked")).toBeVisible();
  await expect(popover.getByText("Archived, not merged")).toBeVisible();
});

test("the branch list offers Restore for archived branches only", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-none.json");
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  await page.getByText("All branches").click();

  await page.getByRole("tab", { name: "Merged (1)" }).click();
  await page.getByRole("button", { name: "Options", exact: true }).click();
  await expect(
    page.getByRole("menuitem", { name: "Branch info" }),
  ).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Restore" })).toBeHidden();
  await expect(page.getByRole("menuitem", { name: "Compare" })).toBeHidden();
  await page.keyboard.press("Escape");

  await page.getByRole("tab", { name: "Archived (1)" }).click();
  await page.getByRole("button", { name: "Options", exact: true }).click();
  await expect(page.getByRole("menuitem", { name: "Restore" })).toBeVisible();
  await page.getByRole("menuitem", { name: "Branch info" }).click();

  const info = page.getByRole("dialog").filter({ hasText: "Branch info" });
  await expect(info.getByRole("button", { name: "Restore" })).toBeVisible();
  await expect(info.getByRole("button", { name: "Compare" })).toBeVisible();
});
