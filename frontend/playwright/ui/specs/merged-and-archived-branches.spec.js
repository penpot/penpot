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

test("an archived branch opens read-only and offers its ways out", async ({
  page,
}) => {
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
  await expect(
    banner.getByRole("button", { name: "Turn into an ordinary file" }),
  ).toBeVisible();
  await expect(page.getByText("Archived, read only")).toBeVisible();
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeHidden();

  // restoring asks first, then reopens the branch, and the banner follows
  // its new status
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
  // the confirmation is a plain modal: its title heads the box that holds
  // the accept button
  await page
    .getByRole("heading", { name: "Restore branch" })
    .locator("../..")
    .getByRole("button", { name: "Restore" })
    .click();
  await restore;
  await expect(banner.getByText("1 change not in main")).toBeVisible();
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeVisible();
});

test("an archived branch asks before turning into an ordinary file", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-archived.json");
  await workspacePage.goToWorkspace();

  const banner = page.locator("#branch-banner");
  await banner
    .getByRole("button", { name: "Turn into an ordinary file" })
    .click();
  await expect(
    page.getByText("Turn this branch into an ordinary file"),
  ).toBeVisible();
});

test("the switcher groups archived branches apart and lists no merged ones", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-none.json");
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  const popover = page.getByRole("dialog", { name: "Branches" });

  // a merge deletes its branch: no group lists merged branches
  await expect(popover.getByRole("button", { name: /^Merged/ })).toBeHidden();
  await expect(popover.getByText("shipped")).toBeHidden();
  await expect(popover.getByText("parked")).toBeHidden();

  await popover.getByRole("button", { name: /^Archived/ }).click();
  await expect(popover.getByText("parked")).toBeVisible();
  await expect(popover.getByText("Archived, read only")).toBeVisible();
});

test("the branch list offers Restore for archived branches only", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchFile(workspacePage, "get-file-branch-info-none.json");
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  await page.getByText("All branches").click();

  // a merge deletes its branch: no tab lists merged branches
  await expect(page.getByRole("tab", { name: /^Merged/ })).toBeHidden();

  await page.getByRole("tab", { name: "Archived (1)" }).click();
  await page.getByRole("button", { name: "Options", exact: true }).click();
  await expect(page.getByRole("menuitem", { name: "Restore" })).toBeVisible();
  await expect(
    page.getByRole("menuitem", { name: "Turn into an ordinary file" }),
  ).toBeVisible();
  await page.getByRole("menuitem", { name: "Branch info" }).click();

  const info = page.getByRole("dialog").filter({ hasText: "Branch info" });
  await expect(info.getByRole("button", { name: "Restore" })).toBeVisible();
  await expect(info.getByRole("button", { name: "Compare" })).toBeVisible();
  await expect(
    info.getByRole("button", { name: "Turn into an ordinary file" }),
  ).toBeVisible();
});
