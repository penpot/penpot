import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "logged-in-user/get-profile-logged-in-no-onboarding.json",
  );
});

test("Navigate to penpot changelog from profile menu", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await dashboardPage.clickProfileMenuItem("About Penpot");

  // Listen for the new page (tab) that opens when clicking "Penpot Changelog"
  const [newPage] = await Promise.all([
    page.context().waitForEvent("page"),
    dashboardPage.clickProfileMenuItem("Penpot Changelog"),
  ]);

  await newPage.waitForLoadState();
  await expect(newPage).toHaveURL(
    "https://github.com/penpot/penpot/blob/develop/CHANGES.md",
  );
});

test("Opens release notes from current version from profile menu", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await dashboardPage.clickProfileMenuItem("About Penpot");
  await expect(page.getByText("Version 0.0.0 notes")).toBeVisible();
  await dashboardPage.clickProfileMenuItem("Version");
  await expect(page.getByText("new in penpot?")).toBeVisible();
});
