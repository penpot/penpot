import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
});

// New Project 1 is a regular project; Drafts is the is-default pseudo-project
// (see frontend/playwright/data/dashboard/get-projects-full.json), which the
// menu itself renders with rename/duplicate/pin/move-to/delete all hidden.
async function setupTwoProjects(dashboardPage) {
  await dashboardPage.mockRPC(
    "get-projects?team-id=*",
    "dashboard/get-projects-full.json",
  );
  await dashboardPage.setupDrafts();
}

function projectRow(page, name) {
  return page.getByRole("article").filter({ hasText: name });
}

test("User can open a project's options menu from the \"...\" button", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "New Project 1");
  await row.getByTestId("project-options").click();

  const menu = page.getByRole("menu");
  await expect(menu.getByTestId("project-rename")).toBeVisible();
  await expect(menu.getByTestId("project-duplicate")).toBeVisible();
  await expect(menu.getByTestId("project-pin")).toBeVisible();
  await expect(menu.getByTestId("project-delete")).toBeVisible();
  // project-move-to is covered separately below: it only renders once there
  // is at least one other team to move to, which the default single-team
  // fixture used here doesn't have.
});

test("User can open a project's options menu by right-clicking its title", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "New Project 1");
  await row.getByText("New Project 1").click({ button: "right" });

  const menu = page.getByRole("menu");
  await expect(menu.getByTestId("project-rename")).toBeVisible();
  await expect(menu.getByTestId("project-delete")).toBeVisible();
});

test("The default Drafts project has a limited options menu", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "Drafts");
  await row.getByTestId("project-options").click();

  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await expect(menu.getByTestId("project-rename")).toHaveCount(0);
  await expect(menu.getByTestId("project-duplicate")).toHaveCount(0);
  await expect(menu.getByTestId("project-pin")).toHaveCount(0);
  await expect(menu.getByTestId("project-move-to")).toHaveCount(0);
  await expect(menu.getByTestId("project-delete")).toHaveCount(0);
});

test("User can rename a project from the options menu", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "New Project 1");
  await row.getByTestId("project-options").click();
  await page.getByTestId("project-rename").click();

  // Not scoped to `row` (renaming swaps the title for an <input>, whose
  // value doesn't count as text content, so the `hasText` filter used to
  // find the row in the first place would no longer match it) and matched
  // by value rather than role, since the dashboard's own search field is
  // also a textbox.
  await expect(page.locator('input[value="New Project 1"]')).toBeVisible();
});

test("User can delete a project from the options menu", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "New Project 1");
  await row.getByTestId("project-options").click();
  await page.getByTestId("project-delete").click();

  await expect(
    page.getByRole("heading", { name: "Delete project" }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Delete project" }),
  ).toBeVisible();
});

test("The move-to submenu lists the user's other teams", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await setupTwoProjects(dashboardPage);
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "logged-in-user/get-teams-complete.json",
  );
  await dashboardPage.goToDashboard();

  const row = projectRow(page, "New Project 1");
  await row.getByTestId("project-options").click();
  await page.getByTestId("project-move-to").click();

  await expect(
    page.getByRole("menuitem", { name: "Second team" }),
  ).toBeVisible();
});
