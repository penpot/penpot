import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const PR_ID = "c7ce0794-0992-8105-8004-38f280443999";

// The review bar at the bottom of the canvas: the review's own chrome,
// which replaces the branch bar while a design review is open.
const reviewBar = (page) => page.getByRole("region", { name: "Design review" });

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
    "get-file-branch-reviews?file-id=*":
      "pull-requests/get-file-branch-reviews.json",
    "get-branch-review?id=*": "pull-requests/get-branch-review.json",
    "get-file-branch-diff?branch-id=*":
      "pull-requests/get-file-branch-diff-empty.json",
    "submit-branch-review-verdict":
      "pull-requests/submit-branch-review-verdict.json",
  });
};

test("opening a file with pr-id enters the design review", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the review bar names the review being read, and the header trades the
  // file name for the title of the review
  const bar = reviewBar(page);
  await expect(bar).toBeVisible();
  await expect(bar.getByText("In review", { exact: true })).toBeVisible();
  await expect(
    bar.getByText("Checkout redesign", { exact: true }),
  ).toBeVisible();
  await expect(page.getByTitle("Review: Checkout redesign")).toBeVisible();

  // the essentials are visible buttons: the details, the verdict of an
  // assigned reviewer, and the way out
  await expect(bar.getByRole("button", { name: "Details" })).toBeVisible();
  await expect(
    bar.getByRole("button", { name: "Submit review" }),
  ).toBeVisible();
  await expect(bar.getByRole("button", { name: "Exit review" })).toBeVisible();

  // comparing, merging and cancelling live behind the kebab menu
  await expect(bar.getByRole("button", { name: "Compare" })).toBeHidden();
  await bar.getByRole("button", { name: "Options" }).click();
  await expect(page.getByText("Compare", { exact: true })).toBeVisible();
  await expect(page.getByText("Merge to main")).toBeVisible();
  await expect(page.getByText("Cancel review")).toBeVisible();
});

test("comparing shows the proposed changes on the canvas", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // comparing is one of the review bar's options
  const bar = reviewBar(page);
  await bar.getByRole("button", { name: "Options" }).click();
  await page.getByText("Compare", { exact: true }).click();

  // the review bar belongs to the comparison, and the changes take the
  // left sidebar: no dialog opens
  await expect(bar.getByText("Comparing", { exact: true })).toBeVisible();
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(
    page.getByTestId("left-sidebar").getByText("View only"),
  ).toBeVisible();

  // leaving the comparison goes back to the review
  await bar.getByRole("button", { name: "Exit compare" }).click();
  await expect(bar.getByText("In review", { exact: true })).toBeVisible();
});

test("accepting the pull request opens the merge dialog", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const banner = reviewBar(page);
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge to main").click();

  // the branching merge dialog handles the actual integration
  await expect(page.getByText("Merge into main")).toBeVisible();
  await expect(page.getByText("checkout").first()).toBeVisible();
});

test("the review is read-only", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await expect(reviewBar(page)).toBeVisible();

  // the review toolbar replaces the design tools: moving around,
  // commenting and tasks only, so the canvas offers no way to draw
  const toolbar = page.getByRole("toolbar", { name: "Review tools" });
  await expect(toolbar).toBeVisible();
  for (const tool of [
    "Move",
    "Comment",
    "Comment an area",
    "Create a task",
    "Show changes",
  ]) {
    await expect(
      toolbar.getByRole("button", { name: tool, exact: true }),
    ).toBeVisible();
  }
  await expect(workspacePage.rectShapeButton).toBeHidden();

  // and the sidebar flags the file as view only
  await expect(page.getByText("View only")).toBeVisible();

  // the right panel is the review's: the inspect of the selection, the
  // comments and the tasks of the review
  await expect(page.getByRole("tab", { name: "Inspect" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Comments (0)" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "Tasks (0)" })).toBeVisible();
});

test("an assigned reviewer can submit a verdict from the review bar", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the logged-in profile is an assigned reviewer in the fixture, so the
  // bar offers the verdict
  const bar = reviewBar(page);
  await bar.getByRole("button", { name: "Submit review" }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("heading", { name: "Submit review" }),
  ).toBeVisible();

  // the verdicts are a choice of one: radios, each with its hint
  const requestChanges = dialog.getByRole("radio", { name: /Request changes/ });
  await requestChanges.click();
  await expect(requestChanges).toBeChecked();
  await dialog.getByRole("button", { name: "Submit review" }).click();

  await expect(page.getByText("Review submitted")).toBeVisible();
});

test("closing the pull request asks for confirmation", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.mockRPC(
    "close-branch-review",
    "pull-requests/close-branch-review.json",
  );
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const banner = reviewBar(page);
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Cancel review").click();

  // nothing is closed yet: a confirmation dialog takes over
  await expect(page.getByText("will be cancelled")).toBeVisible();

  // confirming closes the pull request; being inside its sandbox, the
  // user is taken back to the file (no pr-id in the url)
  await page.getByRole("button", { name: "Cancel design review" }).click();
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
    "get-file-branch-reviews?file-id=*":
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

  const banner = page.locator("#branch-banner");
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge to main").click();
  await expect(page.getByText("Merge into main")).toBeVisible();
});

test("exiting the review takes the user back to the file", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the way out is an icon button on the bar, named by its tooltip
  const banner = reviewBar(page);
  await expect(banner).toBeVisible();
  await banner.getByRole("button", { name: "Exit review" }).click();

  // exiting drops the review context and the pr-id param; the file (the
  // live branch) stays as it was
  await expect(page).not.toHaveURL(/pr-id/);
  await expect(reviewBar(page)).toBeHidden();
});

test("during a review the header names the branch under review", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  // the review link opens the author's branch
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "pull-requests/get-file-branch-info-branch.json",
  );
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the review bar replaces the branch bar, so the header loads the
  // branch context itself
  await expect(reviewBar(page)).toBeVisible();
  await expect(page.getByTitle("On branch checkout from main")).toBeVisible();
});

test("a branch that cannot be compared with main says so", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  // the backend answers nil counts when it cannot compare the branch
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "pull-requests/get-file-branch-info-uncompared.json",
  );
  await workspacePage.goToWorkspace();

  const banner = page.locator("#branch-banner");
  await expect(
    banner.getByText("Couldn't compare this branch with main"),
  ).toBeVisible();
  await expect(banner.getByText("Up to date with main")).toBeHidden();
});

test("on a branch, the all-branches dialog offers no new branch", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupPullRequestSandbox(workspacePage);
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "pull-requests/get-file-branch-info-branch.json",
  );
  await workspacePage.goToWorkspace();

  await page.getByTitle("On branch checkout from main").click();
  await page.getByText("All branches").click();

  // branches only grow from main: the button stays, disabled
  await expect(page.getByRole("button", { name: "New branch" })).toBeDisabled();
});
