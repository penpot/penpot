import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

// A viewer's permission is not a frontend rule: `:can-edit` arrives in the
// `get-teams` payload and the dashboard components read it inline, so there is
// no pure predicate to move to a unit test. These tests keep the browser proof
// and walk the sections a viewer reaches from the sidebar.

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);

  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "logged-in-user/get-teams-role-viewer.json",
  );
});

test("A viewer sees no project or file edit affordances", async ({ page }) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDashboard();

  await test.step("no project creation in the recent files section", async () => {
    await expect(dashboardPage.addProjectButton).toHaveCount(0);
    await expect(
      dashboardPage.page.getByTestId("empty-placeholder"),
    ).toBeVisible();
  });

  await test.step("no file creation in the drafts section", async () => {
    await dashboardPage.sidebar.getByText("Drafts", { exact: true }).click();
    await expect(dashboardPage.mainHeading).toHaveText("Drafts");
    await expect(page.getByTestId("new-file")).toHaveCount(0);
  });

  await test.step("no edit options in the file context menu", async () => {
    const button = page.getByRole("button", { name: /New File 2/ });
    await button.click();
    await button.click({ button: "right" });

    // A viewer keeps the three unconditional items (open in a new tab, copy
    // link and download). Anything gated by `can-edit` that leaks into the
    // menu makes this count grow, which is what the four labels below only
    // describe.
    await expect(page.getByRole("menuitem")).toHaveCount(3);

    await expect(page.getByText("rename")).toBeHidden();
    await expect(page.getByText("duplicate")).toBeHidden();
    await expect(page.getByText("add as shared library")).toBeHidden();
    await expect(page.getByText("delete")).toBeHidden();
  });
});

test("A viewer can copy a file's link from the context menu", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();

  const button = page.getByRole("button", { name: /New File 2/ });
  await button.click({ button: "right" });
  await page.getByTestId("file-copy-link").click();

  await expect(
    page.getByRole("alert").filter({ hasText: "Link copied to clipboard" }),
  ).toBeVisible();

  const link = await dashboardPage.copiedLink();
  expect(link.searchParams.get("screen")).toBe("workspace");
  expect(link.searchParams.get("team-id")).toBe(DashboardPage.anyTeamId);
  expect(link.searchParams.get("file-id")).toBe(
    "8b479b80-e02d-8074-8004-4088dc6bfd11",
  );
});

test("A viewer cannot open a pinned project's menu from the sidebar", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.mockRPC(
    "get-projects?team-id=*",
    "dashboard/get-projects-pinned.json",
  );
  await dashboardPage.goToDashboard();

  await page
    .getByTestId("pinned-projects")
    .getByText("New Project 1")
    .click({ button: "right" });

  await expect(page.getByRole("menu")).toHaveCount(0);
});

test("A viewer's multiple selection menu has no Copy link and no separator", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();

  const button = page.getByRole("button", { name: /New File 1/ });
  await button.click();
  await page
    .getByRole("button", { name: /New File 2/ })
    .click({ modifiers: ["Shift"] });
  await button.click({ button: "right" });

  const menu = page.getByRole("menu");
  await expect(menu.getByTestId("file-binary-export-multi")).toBeVisible();
  await expect(menu.getByTestId("file-copy-link")).toHaveCount(0);
  await expect(menu.getByRole("separator")).toHaveCount(0);
});

test("A viewer cannot add a custom font", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToFonts();

  await expect(page.getByTestId("add-custom-font")).toHaveCount(0);
});
