import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
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

async function setupOrganizationTeamSwitcher(page) {
  await DashboardPage.mockConfigFlags(page, [
    "disable-onboarding",
    "enable-admin-console",
  ]);
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "dashboard/get-teams-organization-switcher.json",
  );
  await DashboardPage.mockRPC(
    page,
    "check-nitrate-sso",
    "dashboard/check-nitrate-sso-authorized.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();
  return dashboardPage;
}

test("User sees the destination organization when creating a team", async ({
  page,
}) => {
  const organizationName =
    "Product Studio With a Long Organization Name That Must Remain Available";
  const dashboardPage = await setupOrganizationTeamSwitcher(page);

  await dashboardPage.teamDropdown.click();
  await page.getByRole("menuitem", { name: organizationName }).click();
  await page.getByRole("menuitem", { name: "Create new team" }).click();

  const title = page.getByRole("heading", {
    name: `Create new team in ${organizationName}`,
    exact: true,
  });
  await expect(title).toBeVisible();
  await expect(title).toHaveText(`Create new team in ${organizationName}`);

  const titleFits = await title.evaluate(
    (element) => element.scrollWidth <= element.clientWidth,
  );
  expect(titleFits).toBe(true);

  const modal = title.locator("xpath=../../..");
  const modalBounds = await modal.boundingBox();
  expect(modalBounds.width).toBeLessThanOrEqual(512);
});

test("Creating a team under Other teams does not name an organization", async ({
  page,
}) => {
  const dashboardPage = await setupOrganizationTeamSwitcher(page);

  await dashboardPage.teamDropdown.click();
  await page.getByRole("menuitem", { name: "Other teams" }).click();
  await page.getByRole("menuitem", { name: "Create new team" }).click();

  await expect(
    page.getByRole("heading", { name: "Create new team", exact: true }),
  ).toBeVisible();
});
