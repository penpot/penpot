import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const LISTING = "**/api/main/methods/get-file-branches?file-id=*";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-branching"]);
});

// main, with two branches (one of them archived)
const setupMainWithBranches = async (workspacePage) => {
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPCs({
    "get-file-branch-info?file-id=*":
      "pull-requests/get-file-branch-info-none.json",
    "get-file-branch-count?file-id=*":
      "branches/get-file-branch-count-two.json",
    "get-file-branches?file-id=*": "branches/get-file-branches-two.json",
    "get-file-pull-requests?file-id=*":
      "pull-requests/get-file-branches-empty.json",
  });
};

// every listing request fails until `heal` is called
const failListing = async (page) => {
  let failing = true;
  await page.route(LISTING, (route) =>
    failing
      ? route.fulfill({
          status: 500,
          contentType: "application/transit+json",
          body: JSON.stringify({ "~:type": "~:internal" }),
        })
      : route.fallback(),
  );
  return () => {
    failing = false;
  };
};

test("opening a file counts branches without listing them", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupMainWithBranches(workspacePage);
  const listings = [];
  page.on("request", (request) => {
    if (request.url().includes("/get-file-branches?")) listings.push(request);
  });

  await workspacePage.goToWorkspace();

  // the count alone shows the row
  const chip = page.getByTitle("Switch branch");
  await expect(chip).toBeVisible();
  expect(listings).toHaveLength(0);

  // the listing loads when the switcher opens
  await chip.click();
  await expect(page.getByText("checkout", { exact: true })).toBeVisible();
  expect(listings).toHaveLength(1);
});

test("the switcher shows a loading state while the listing loads", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupMainWithBranches(workspacePage);
  let release;
  const released = new Promise((resolve) => {
    release = resolve;
  });
  await page.route(LISTING, async (route) => {
    await released;
    await route.fallback();
  });
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  await expect(page.getByText("Loading branches…")).toBeVisible();
  await expect(page.getByText("checkout", { exact: true })).toBeHidden();

  release();
  await expect(page.getByText("checkout", { exact: true })).toBeVisible();
  await expect(page.getByText("Loading branches…")).toBeHidden();
});

test("a failed listing in the switcher offers a retry and keeps the row", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupMainWithBranches(workspacePage);
  const heal = await failListing(page);
  await workspacePage.goToWorkspace();

  const chip = page.getByTitle("Switch branch");
  await chip.click();
  await expect(page.getByText("Couldn't load branches")).toBeVisible();
  await expect(chip).toBeVisible();

  heal();
  await page.getByRole("button", { name: "Retry" }).click();
  await expect(page.getByText("checkout", { exact: true })).toBeVisible();
  await expect(page.getByText("Couldn't load branches")).toBeHidden();
});

test("a failed listing in the all-branches dialog offers a retry", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupMainWithBranches(workspacePage);
  const heal = await failListing(page);
  await workspacePage.goToWorkspace();

  await page.getByTitle("Switch branch").click();
  await page.getByText("All branches").click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByText("Couldn't load branches")).toBeVisible();

  heal();
  await dialog.getByRole("button", { name: "Retry" }).click();
  await expect(dialog.getByText("checkout", { exact: true })).toBeVisible();
});

test("a failed count shows the row with an error, and the switcher lists", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await setupMainWithBranches(workspacePage);
  await page.route(
    "**/api/main/methods/get-file-branch-count?file-id=*",
    (route) =>
      route.fulfill({
        status: 500,
        contentType: "application/transit+json",
        body: JSON.stringify({ "~:type": "~:internal" }),
      }),
  );
  await workspacePage.goToWorkspace();

  // an unknown count never hides the row: the note says what failed
  const chip = page.getByTitle("Switch branch");
  await expect(chip).toBeVisible();
  await expect(page.getByText("Couldn't load branches")).toBeVisible();

  // opening the switcher loads the listing, which settles the count
  await chip.click();
  await expect(page.getByText("checkout", { exact: true })).toBeVisible();
  await expect(page.getByText("Couldn't load branches")).toBeHidden();
});
