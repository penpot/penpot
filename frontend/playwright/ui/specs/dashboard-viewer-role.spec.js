import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "logged-in-user/get-profile-logged-in-no-onboarding.json",
  );
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "logged-in-user/get-teams-role-viewer.json",
  );
});

test("User can't create a new project", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();
  await expect(dashboardPage.addProjectButton).toBeHidden();
});

test("User has an empty placeholder", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();
  await expect(
    dashboardPage.page.getByTestId("empty-placeholder"),
  ).toBeVisible();
});

test("User hasn't context menu options for edit file", async ({ page }) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();

  const button = dashboardPage.page.getByRole("button", { name: /New File 2/ });
  await button.click();
  await button.click({ button: "right" });

  await expect(dashboardPage.page.getByText("rename")).toBeHidden();
  await expect(dashboardPage.page.getByText("duplicate")).toBeHidden();
  await expect(
    dashboardPage.page.getByText("add as shared library"),
  ).toBeHidden();
  await expect(dashboardPage.page.getByText("delete")).toBeHidden();
});

test("User hasn't create file button", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();
  await expect(dashboardPage.page.getByText("+ New File")).toBeHidden();
});

test("User hasn't add font button", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToFonts();
  await expect(dashboardPage.page.getByText("add custom font")).toBeHidden();
});
