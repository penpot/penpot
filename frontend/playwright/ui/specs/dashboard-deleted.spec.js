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

test.describe("Dashboard Deleted Page", () => {
  test("User can navigate to deleted page", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);

    // Setup mock for deleted files API
    await dashboardPage.setupDeletedFiles();

    // Navigate directly to deleted page
    await dashboardPage.goToDeleted();

    // Check for the restore all and clear trash buttons
    await expect(
      page.getByRole("button", { name: "Restore All" }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Clear trash" }),
    ).toBeVisible();
  });
});
