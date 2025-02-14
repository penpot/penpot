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

test("BUG 10421 - Fix libraries context menu", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.mockRPC(
    "get-team-shared-files?team-id=*",
    "dashboard/get-team-shared-files-10142.json",
  );

  await dashboardPage.mockRPC(
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  await dashboardPage.goToLibraries();

  const libraryItem = page.getByTitle(/Lorem Ipsum/);

  await expect(libraryItem).toBeVisible();
  await libraryItem.getByRole("button", { name: "Options" }).click();

  await expect(page.getByText("Rename")).toBeVisible();
});
