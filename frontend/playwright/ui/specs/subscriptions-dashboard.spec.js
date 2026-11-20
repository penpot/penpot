import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

// These specs prove the wiring between the mocked RPC data and what the
// dashboard renders: the plan icon, the sidebar plan name, the team
// settings link and the members CTAs. The rules themselves are pure
// functions with unit tests of their own.

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

async function setupTeamSettings(page, { profile, teams, members }) {
  await DashboardPage.mockRPC(page, "get-profile", profile);

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(page, "get-teams", teams);

  await DashboardPage.mockRPC(
    page,
    "get-projects?team-id=*",
    "dashboard/get-projects-second-team.json",
  );

  await DashboardPage.mockRPC(page, "get-team-members?team-id=*", members);
  await dashboardPage.setupTeamSettings();

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await dashboardPage.goToSecondTeamSettingsSection();
  return dashboardPage;
}

test("Team with unlimited subscription has specific icon in menu", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "subscription/get-profile-unlimited-subscription.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-owner.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-projects?team-id=*",
    "dashboard/get-projects-second-team.json",
  );
  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );
  await dashboardPage.goToSecondTeamDashboard();
  const icon = page.getByTestId("subscription-icon");
  await expect(icon).toBeVisible();
  await expect(icon.locator("use")).toHaveAttribute(
    "href",
    "#icon-character-u",
  );
});

test("The Unlimited subscription has its name in the sidebar dropdown", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "subscription/get-profile-unlimited-subscription.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage-one-editor.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-owner.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );
  await dashboardPage.goToDashboard();

  await expect(page.getByTestId("subscription-name")).toHaveText(
    "Unlimited plan (trial)",
  );
});

test("An unpaid subscription reads as professional in the sidebar dropdown", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "subscription/get-profile-unlimited-unpaid-subscription.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-owner.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );
  await dashboardPage.goToDashboard();

  await expect(page.getByTestId("subscription-name")).toHaveText(
    "Professional plan",
  );
});

test("Team settings shows the plan and hides the manage link from members", async ({
  page,
}) => {
  const dashboardPage = await setupTeamSettings(page, {
    profile: "logged-in-user/get-profile-logged-in.json",
    teams: "subscription/get-teams-unlimited-subscription-member.json",
    members: "dashboard/get-team-members-admin.json",
  });

  await expect(page.getByText("Unlimited (trial)")).toBeVisible();
  await expect(page.getByTestId("manage-subscription-link")).toHaveCount(0);

  // The same team read as an owner shows the link, so the component gets
  // the permission from the team and not from a constant.
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-owner.json",
  );
  await DashboardPage.mockRPC(
    page,
    "get-team-members?team-id=*",
    "dashboard/get-team-members-owner.json",
  );
  await dashboardPage.goToSecondTeamSettingsSection();

  await expect(page.getByText("Unlimited (trial)")).toBeVisible();
  await expect(page.getByTestId("manage-subscription-link")).toBeVisible();
});

test("Members and Invitations tabs warn when editors exceed seats", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "subscription/get-profile-unlimited-subscription.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-owner.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-projects?team-id=*",
    "dashboard/get-projects-second-team.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-members?team-id=*",
    "subscription/get-team-members-subscription-eight-member.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await dashboardPage.goToSecondTeamMembersSection();

  // The sidebar banner and the members CTA, in that order.
  await expect(
    page.getByText("Your team keeps growing!", { exact: false }),
  ).toBeVisible();
  await expect(
    page.getByText("Inviting people while on the unlimited plan"),
  ).toBeVisible();
  await expect(page.getByTestId("cta")).toHaveCount(2);

  await DashboardPage.mockRPC(
    page,
    "get-team-invitations?team-id=*",
    "subscription/get-team-invitations.json",
  );
  await dashboardPage.goToSecondTeamInvitationsSection();

  await expect(
    page.getByText("Inviting people while on the unlimited plan"),
  ).toBeVisible();
  await expect(page.getByTestId("cta")).toHaveCount(2);
});
