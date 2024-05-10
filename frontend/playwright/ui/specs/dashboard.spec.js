import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
});

test("Dashboad page has title ", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToWorkspace();

  await expect(dashboardPage.page).toHaveURL(/dashboard/);
  await expect(dashboardPage.titleLabel).toBeVisible();
});

test("User can create a new project", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupNewProject();

  await dashboardPage.goToWorkspace();
  await dashboardPage.addProjectBtn.click();

  await expect(dashboardPage.projectName).toBeVisible();
});

test("User goes to draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDraftsEmpty();

  await dashboardPage.goToWorkspace();
  await dashboardPage.draftLink.click();

  await expect(dashboardPage.draftTitle).toBeVisible();
});

test("User loads the draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();

  await dashboardPage.goToDrafts();

  await expect(dashboardPage.draftsFile).toBeVisible();
});
