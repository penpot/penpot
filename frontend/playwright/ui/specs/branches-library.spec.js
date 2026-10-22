import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

const setupBranching = async (workspacePage, rpcs) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-none.json",
    "get-file-branches?file-id=*": "pull-requests/get-file-branches-empty.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-branches-empty.json",
    ...rpcs,
  });
};

test("on a branch of a library, the header says consumers read main", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranching(workspacePage, {
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-library-branch.json",
  });
  await workspacePage.goToWorkspace();

  const chip = page.getByTitle(
    "On branch checkout from main. Library consumers can adopt changes from main only.",
  );
  await expect(chip).toBeVisible();

  await chip.click();
  await expect(
    page.getByText("Default branch (read by library consumers)"),
  ).toBeVisible();
});

test("on a library, the create dialog says consumers read main", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranching(workspacePage, {
    "get-file-branches?file-id=*": "pull-requests/get-file-branches-one.json",
  });
  await workspacePage.mockGetFile("pull-requests/get-file-blank-shared.json");
  await workspacePage.goToWorkspace();

  await expect(
    page.getByText("Default branch (read by library consumers)"),
  ).toBeVisible();

  await page.getByTitle("Switch branch").click();
  await page.getByText("New branch").click();
  await expect(
    page.getByText(
      "files that use this library can adopt changes from main only",
    ),
  ).toBeVisible();
});

test("on an ordinary file, the create dialog keeps its note", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranching(workspacePage, {
    "get-file-branches?file-id=*": "pull-requests/get-file-branches-one.json",
  });
  await workspacePage.goToWorkspace();

  await expect(page.getByText("Default branch", { exact: true })).toBeVisible();

  await page.getByTitle("Switch branch").click();
  await page.getByText("New branch").click();
  await expect(page.getByText("isolated copy")).toBeVisible();
  await expect(page.getByText("files that use this library")).toBeHidden();
});
