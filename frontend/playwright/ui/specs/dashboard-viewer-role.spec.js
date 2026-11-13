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
    await expect(dashboardPage.addProjectButton).toBeHidden();
    await expect(
      dashboardPage.page.getByTestId("empty-placeholder"),
    ).toBeVisible();
  });

  await test.step("no file creation in the drafts section", async () => {
    await dashboardPage.sidebar.getByText("Drafts", { exact: true }).click();
    await expect(dashboardPage.mainHeading).toHaveText("Drafts");
    await expect(page.getByText("+ New File")).toBeHidden();
  });

  await test.step("no edit options in the file context menu", async () => {
    const button = page.getByRole("button", { name: /New File 2/ });
    await button.click();
    await button.click({ button: "right" });

    await expect(page.getByText("rename")).toBeHidden();
    await expect(page.getByText("duplicate")).toBeHidden();
    await expect(page.getByText("add as shared library")).toBeHidden();
    await expect(page.getByText("delete")).toBeHidden();
  });
});

test("A viewer cannot add a custom font", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToFonts();

  await expect(page.getByText("add custom font")).toBeHidden();
});
