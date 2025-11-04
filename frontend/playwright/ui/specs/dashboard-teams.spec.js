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

test("BUG 12359 - Selected invitations count is not pluralized", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitations();

  await dashboardPage.goToSecondTeamInvitationsSection();

  await expect(page.getByText("test1@mail.com")).toBeVisible();

  // NOTE: we cannot use check() or getByLabel() because the checkbox
  // is hidden inside the label.
  await page.getByText("test1@mail.com").click();
  await expect(page.getByText("1 invitation selected")).toBeVisible();

  await page.getByText("test2@mail.com").check();
  await expect(page.getByText("2 invitations selected")).toBeVisible();
});
