import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const BRAND_KEY = "color//c7ce0794-0992-8105-8004-38f2804439a1";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

// The open file is the branch "checkout"; `diff` is what comparing it with
// main lists, in either direction.
const setupBranch = async (workspacePage, diff) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-branch.json",
    "get-file-branches?file-id=*": "pull-requests/get-file-branches-empty.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-branches-empty.json",
    "get-file-tasks?file-id=*": "pull-requests/get-file-branches-empty.json",
    "get-branch-diff?branch-id=*": `compare/${diff}`,
    "get-branch-change-reviews?branch-id=*":
      "compare/get-branch-change-reviews-empty.json",
    "set-branch-change-review": "compare/set-branch-change-review.json",
  });
};

const banner = (page) => page.locator("#branch-banner");
const changes = (page) => page.getByTestId("left-sidebar");
const detail = (page) => page.getByTestId("right-sidebar");

const enterCompare = async (page) => {
  await banner(page).getByRole("button", { name: "Compare" }).click();
  await expect(banner(page).getByText("Comparing with main")).toBeVisible();
};

test("comparing takes the canvas, read-only, and Escape leaves it", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranch(workspacePage, "get-branch-diff-changes.json");
  await workspacePage.goToWorkspace();
  await enterCompare(page);

  // no dialog: the changes take the left sidebar and the drawing tools go
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(changes(page).getByText("View only")).toBeVisible();
  await expect(
    changes(page).getByRole("tab", { name: "In this branch (2)" }),
  ).toBeVisible();
  await expect(changes(page).getByText("Brand")).toBeVisible();
  await expect(changes(page).getByText("Heading")).toBeVisible();
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeHidden();
  await expect(
    page.getByRole("toolbar", { name: "Compare tools" }),
  ).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(banner(page).getByText("1 change not in main")).toBeVisible();
  await expect(
    page.getByRole("toolbar", { name: "Drawing tools" }),
  ).toBeVisible();
});

test("a change left out stays out of the merge", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranch(workspacePage, "get-branch-diff-changes.json");
  await workspacePage.goToWorkspace();
  await enterCompare(page);

  await expect(
    banner(page).getByRole("button", { name: "Merge 2 changes" }),
  ).toBeVisible();

  // the list opens on its first change: its detail says what it did, and
  // leaves it out
  await expect(detail(page).getByText("#000000")).toBeVisible();
  await expect(detail(page).getByText("#ff0000")).toBeVisible();
  await detail(page)
    .getByRole("button", { name: "Leave out of merge" })
    .click();
  await expect(
    changes(page).getByText("Left out", { exact: true }),
  ).toBeVisible();
  await expect(banner(page).getByText("1 left out")).toBeVisible();

  // merging asks first, and sends what was left out
  const merge = page.waitForRequest(
    (req) =>
      req.url().includes("merge-file-branch") &&
      (req.postData() ?? "").includes(BRAND_KEY),
  );
  await banner(page).getByRole("button", { name: "Merge 1 change" }).click();
  await expect(page.getByText("1 change is left out.")).toBeVisible();
  await page.getByRole("button", { name: "Merge", exact: true }).click();
  await merge;
});

test("main's changes are listed apart and update the branch", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranch(workspacePage, "get-branch-diff-changes.json");
  await workspacePage.goToWorkspace();
  await enterCompare(page);

  const incoming = page.waitForRequest(
    (req) =>
      req.url().includes("get-branch-diff") &&
      req.url().includes("main-%3Ebranch"),
  );
  await changes(page).getByRole("tab", { name: "In main" }).click();
  await incoming;
  await expect(banner(page).getByText("main has 2 new changes")).toBeVisible();
  await expect(
    changes(page).getByRole("tab", { name: "In main (2)" }),
  ).toBeVisible();
  // the other direction keeps the count it had
  await expect(
    changes(page).getByRole("tab", { name: "In this branch (2)" }),
  ).toBeVisible();

  const update = page.waitForRequest((req) =>
    req.url().includes("update-branch-from-main"),
  );
  await banner(page)
    .getByRole("button", { name: "Update with 2 changes" })
    .click();
  await page.getByRole("button", { name: "Update", exact: true }).click();
  await update;
});

test("conflicts are decided in the sidebar before they apply", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupBranch(workspacePage, "get-branch-diff-conflicts.json");
  await workspacePage.goToWorkspace();
  await enterCompare(page);

  await banner(page)
    .getByRole("button", { name: "Resolve 3 decisions" })
    .click();
  await expect(banner(page).getByText("3 conflicts with main")).toBeVisible();
  await expect(banner(page).getByText("0 of 3 decided")).toBeVisible();
  const apply = banner(page).getByRole("button", { name: "Apply and merge" });
  await expect(apply).toBeDisabled();
  await expect(changes(page).getByText("Needs a decision (3)")).toBeVisible();

  // a property: the value of a side keeps that side. The list opens on
  // its first conflict; a click on its row would let it go
  await detail(page).getByRole("button", { name: "#ff0000" }).click();
  await expect(banner(page).getByText("1 of 3 decided")).toBeVisible();

  // a text: one whole text is kept
  await changes(page).getByText("Legal footnote").click();
  await expect(detail(page).getByText("¶1 Our terms apply.")).toBeVisible();
  await detail(page)
    .getByRole("radio")
    .filter({ hasText: "The terms of sale apply." })
    .click();
  await expect(banner(page).getByText("2 of 3 decided")).toBeVisible();
  await expect(apply).toBeDisabled();

  // an entity one side deleted: one of the two versions
  await changes(page).getByText("Heading").click();
  await expect(detail(page).getByText("This version deletes it")).toBeVisible();
  await detail(page).getByRole("radio").last().click();
  await expect(banner(page).getByText("All conflicts decided")).toBeVisible();
  await expect(changes(page).getByText("Decided (3)")).toBeVisible();

  const merge = page.waitForRequest((req) => {
    const body = req.postData() ?? "";
    return (
      req.url().includes("merge-file-branch") &&
      body.includes("38f2804439b1") &&
      body.includes("38f2804439b2") &&
      body.includes("38f2804439b3")
    );
  });
  await apply.click();
  await merge;
});
