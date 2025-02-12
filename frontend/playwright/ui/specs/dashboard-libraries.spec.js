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
    "dashboard/get-team-shared-files-10421.json",
  );

  await dashboardPage.goToLibraries();

  await expect(page.getByText("Lorem Ipsum")).toBeVisible();
  await page
    .getByRole("button", { name: /Lorem Ipsum/ })
    .click({ button: "right" });

  await expect(page.getByText("Rename")).toBeVisible();

  expect(false).toBe(true);
});
