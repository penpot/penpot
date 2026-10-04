import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const PR_ID = "c7ce0794-0992-8105-8004-38f280443999";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

const setupPullRequestSandbox = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-none.json",
    "get-file-branches?file-id=*": "pull-requests/get-file-branches-empty.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-pull-requests.json",
    "get-pull-request?id=*": "pull-requests/get-pull-request.json",
    "get-branch-diff?branch-id=*": "pull-requests/get-branch-diff-empty.json",
    "submit-pull-request-review":
      "pull-requests/submit-pull-request-review.json",
  });
};

test("opening a file with pr-id shows the review sandbox banner", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await expect(page.getByText("Reviewing pull request")).toBeVisible();
  await expect(page.getByText("Checkout redesign").first()).toBeVisible();
  await expect(page.getByText("checkout → New File 1")).toBeVisible();

  // only the essential actions are visible buttons...
  await expect(page.getByRole("button", { name: "Details" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Compare" })).toBeVisible();

  // ...the rest live behind the kebab menu
  const banner = page.getByText("Reviewing pull request").locator("../../..");
  await banner.getByRole("button", { name: "Options" }).click();
  await expect(page.getByText("Try interactions")).toBeVisible();
  await expect(page.getByText("Merge to main")).toBeVisible();
  await expect(page.getByText("Exit review")).toBeVisible();
});

test("the compare dialog shows the proposed changes", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await page.getByRole("button", { name: "Compare" }).click();

  // the branching compare dialog is reused to inspect the changes
  await expect(page.getByText("Compare changes")).toBeVisible();
});

test("accepting the pull request opens the merge dialog", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const banner = page.getByText("Reviewing pull request").locator("../../..");
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge to main").click();

  // the branching merge dialog handles the actual integration
  await expect(page.getByText("Merge into main")).toBeVisible();
  await expect(page.getByText("checkout").first()).toBeVisible();
});

test("the review behaves like a normal, editable file", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await expect(page.getByText("Reviewing pull request")).toBeVisible();
  // the file is the live branch, opened normally: the design toolbar is
  // available so reviewers can try and validate anything...
  await expect(workspacePage.rectShapeButton).toBeVisible();
  // ...and none of the read-only / inspect affordances kick in
  await expect(page.getByText("Inspecting code")).toBeHidden();
  await expect(page.getByText("View only")).toBeHidden();
});

test("an assigned reviewer can submit a verdict from the banner", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the logged-in profile is an assigned reviewer in the fixture
  await page.getByRole("button", { name: "Review", exact: true }).click();

  await expect(page.getByText("Submit review").first()).toBeVisible();
  await page.getByText("Request changes", { exact: true }).click();
  await page.getByRole("button", { name: "Submit review" }).click();

  await expect(page.getByText("Review submitted")).toBeVisible();
});

test("closing the pull request asks for confirmation", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.mockRPC(
    "close-pull-request",
    "pull-requests/close-pull-request.json",
  );
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await page.getByRole("button", { name: "Details" }).click();
  await page.getByRole("button", { name: "Close pull request" }).click();

  // nothing is closed yet: a confirmation dialog takes over
  await expect(page.getByText("will disappear")).toBeVisible();

  // confirming closes the pull request; being inside its sandbox, the
  // user is taken back to the file (no pr-id in the url)
  await page.getByRole("button", { name: "Close pull request" }).click();
  await expect(page).not.toHaveURL(/pr-id/);
});

test("on a branch, requesting a review is the primary action", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  // the open file IS a branch without an open pull request
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-branch.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-branches-empty.json",
  });
  await workspacePage.goToWorkspace();

  // merging moved behind the kebab menu; requesting a review is primary
  await expect(
    page.getByRole("button", { name: "Request review" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Merge to main" }),
  ).toBeHidden();

  const banner = page.getByText("On branch").locator("../../..");
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge to main").click();
  await expect(page.getByText("Merge into main")).toBeVisible();
});

test("exiting the review takes the user back to the file", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await expect(page.getByText("Reviewing pull request")).toBeVisible();
  const banner = page.getByText("Reviewing pull request").locator("../../..");
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Exit review").click();

  // exiting drops the review context and the pr-id param; the file (the
  // live branch) stays as it was
  await expect(page).not.toHaveURL(/pr-id/);
  await expect(page.getByText("Reviewing pull request")).toBeHidden();
});
