import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const PR_ID = "c7ce0794-0992-8105-8004-38f280443999";

// The review bar at the bottom of the canvas: the review's own chrome,
// which replaces the branch bar while a branch review is open.
const reviewBar = (page) => page.getByRole("region", { name: "Branch review" });

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

const setupBranchReview = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "branch-reviews/get-file-branch-info-none.json",
    "get-file-branches?file-id=*": "branch-reviews/get-file-branches-empty.json",
    "get-file-branch-reviews?file-id=*":
      "branch-reviews/get-file-branch-reviews.json",
    "get-branch-review?id=*": "branch-reviews/get-branch-review.json",
    "get-file-branch-diff?branch-id=*":
      "branch-reviews/get-file-branch-diff-empty.json",
    "submit-branch-review-verdict":
      "branch-reviews/submit-branch-review-verdict.json",
  });
};

test("opening a file with pr-id enters the branch review", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
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
    bar.getByRole("button", { name: "Review", exact: true }),
  ).toBeVisible();
  await expect(bar.getByRole("button", { name: "Exit review" })).toBeVisible();

  // comparing, merging and closing live behind the kebab menu
  await expect(bar.getByRole("button", { name: "Compare" })).toBeHidden();
  await bar.getByRole("button", { name: "Options" }).click();
  await expect(page.getByText("Compare", { exact: true })).toBeVisible();
  await expect(page.getByText("Merge into main")).toBeVisible();
  await expect(page.getByText("Close review")).toBeVisible();
});

test("comparing shows the proposed changes on the canvas", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
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

test("accepting the branch review opens the merge dialog", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const banner = reviewBar(page);
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge into main").click();

  // the branching merge dialog handles the actual integration
  await expect(
    page.getByRole("dialog").getByText("Merge into main"),
  ).toBeVisible();
  await expect(page.getByText("checkout").first()).toBeVisible();
});

test("the review is read-only", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
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

test("without a comment, a verdict button submits at once", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the logged-in profile is an assigned reviewer in the fixture, so the
  // bar offers the verdict
  const bar = reviewBar(page);
  await bar.getByRole("button", { name: "Review", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(
    dialog.getByRole("heading", { name: "Checkout redesign" }),
  ).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Send" })).toBeHidden();

  await dialog.getByRole("button", { name: "Request changes" }).click();

  await expect(page.getByText("Review submitted")).toBeVisible();
});

test("with a comment, the verdict buttons pick and Send submits", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const bar = reviewBar(page);
  await bar.getByRole("button", { name: "Review", exact: true }).click();

  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Add a comment" }).click();

  // Send waits for a verdict
  const send = dialog.getByRole("button", { name: "Send" });
  await expect(send).toBeDisabled();

  await dialog.getByRole("textbox").fill("Looks good");
  const approve = dialog.getByRole("button", { name: "Approve" });
  await approve.click();

  // picking a verdict submits nothing yet
  await expect(approve).toHaveAttribute("aria-pressed", "true");
  await expect(dialog).toBeVisible();

  await send.click();
  await expect(page.getByText("Review submitted")).toBeVisible();
});

test("closing the branch review asks for confirmation", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  await workspacePage.mockRPC(
    "close-branch-review",
    "branch-reviews/close-branch-review.json",
  );
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  const banner = reviewBar(page);
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Close review").click();

  // nothing is closed yet: a confirmation dialog takes over
  await expect(page.getByText("will be closed")).toBeVisible();

  // confirming closes the branch review; being inside its sandbox, the
  // user is taken back to the file (no pr-id in the url)
  await page.getByRole("button", { name: "Close review" }).click();
  await expect(page).not.toHaveURL(/pr-id/);
});

test("on a branch, requesting a review is the primary action", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  // the open file IS a branch without an open branch review
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "branch-reviews/get-file-branch-info-branch.json",
    "get-file-branch-reviews?file-id=*":
      "branch-reviews/get-file-branches-empty.json",
  });
  await workspacePage.goToWorkspace();

  // merging moved behind the kebab menu; requesting a review is primary
  await expect(
    page.getByRole("button", { name: "Request review" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Merge into main" }),
  ).toBeHidden();

  const banner = page.locator("#branch-banner");
  await banner.getByRole("button", { name: "Options" }).click();
  await page.getByText("Merge into main").click();
  await expect(
    page.getByRole("dialog").getByText("Merge into main"),
  ).toBeVisible();
});

test("exiting the review takes the user back to the file", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
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
  await setupBranchReview(workspacePage);
  // the review link opens the author's branch
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "branch-reviews/get-file-branch-info-branch.json",
  );
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  // the review bar replaces the branch bar, so the header loads the
  // branch context itself
  await expect(reviewBar(page)).toBeVisible();
  await expect(page.getByTitle("On branch checkout from main")).toBeVisible();
});

test("a branch that cannot be compared with main says so", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  // the backend answers nil counts when it cannot compare the branch
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "branch-reviews/get-file-branch-info-uncompared.json",
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
  await setupBranchReview(workspacePage);
  await workspacePage.mockRPC(
    "get-file-branch-info?file-id=*",
    "branch-reviews/get-file-branch-info-branch.json",
  );
  await workspacePage.goToWorkspace();

  await page.getByTitle("On branch checkout from main").click();
  await page.getByText("All branches").click();

  // branches only grow from main: the button stays, disabled
  await expect(page.getByRole("button", { name: "New branch" })).toBeDisabled();
});

test("a card of the activity draws the layer as its sitting left it", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranchReview(workspacePage);
  await workspacePage.mockRPCs({
    "get-branch-review-activity?id=*":
      "branch-reviews/get-branch-review-activity.json",
    "get-branch-layer-at-revision?*":
      "branch-reviews/get-branch-layer-at-revision-approximate.json",
  });
  const layerRequest = page.waitForRequest(/get-branch-layer-at-revision/);
  await workspacePage.goToWorkspace({ extraParams: `&pr-id=${PR_ID}` });

  await reviewBar(page).getByRole("button", { name: "Details" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("tab", { name: "Activity (1)" }).click();

  // the layer is not in the open file: the card draws the one the
  // command rebuilt at the revision of the sitting
  await dialog.getByText("Checkout button").hover();
  const request = await layerRequest;
  const params = new URL(request.url()).searchParams;
  expect(params.get("revn")).toBe("7");
  expect(params.get("branch-id")).toBe(
    "c7ce0794-0992-8105-8004-38f280443998",
  );

  const card = page.getByRole("tooltip");
  await expect(card.locator('svg[viewBox="40 30 120 54"]')).toBeVisible();

  // the branch no longer keeps that save, so the card says it shows the
  // layer as it is now
  await expect(card.getByText("Shown as it is now")).toBeVisible();
});
