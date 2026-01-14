import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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
  await expect(page.getByTestId("subscription-icon")).toBeVisible();
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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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

test("The sidebar dropdown displays the correct subscription name when status is Unpaid", async ({
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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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

test("The sidebar dropdown displays the correct subscription name when status is cancelled", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "subscription/get-profile-enterprise-canceled-subscription.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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

test("Team settings has susbscription name and no manage subscription link when is member", async ({
  page,
}) => {
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "logged-in-user/get-profile-logged-in.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-subscription-usage",
    "subscription/get-subscription-usage.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "subscription/get-teams-unlimited-subscription-member.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-projects?team-id=*",
    "dashboard/get-projects-second-team.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-members?team-id=*",
    "subscription/get-team-members-subscription-member.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-stats?team-id=*",
    "dashboard/get-team-stats.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await dashboardPage.goToSecondTeamSettingsSection();
  await expect(page.getByText("Unlimited (trial)")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Manage your subscription" }),
  ).not.toBeVisible();
});

test("Team settings has susbscription name and manage subscription link when is owner", async ({
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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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
    "subscription/get-team-members-subscription-owner.json",
  );

  await DashboardPage.mockRPC(
    page,
    "get-team-stats?team-id=*",
    "dashboard/get-team-stats.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await dashboardPage.goToSecondTeamSettingsSection();

  await expect(page.getByText("Unlimited (trial)")).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Manage your subscription" }),
  ).toBeVisible();
});

test("Members tab has warning message when user has more seats than editors", async ({
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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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

  const ctas = page.getByTestId("cta");
  await expect(ctas).toHaveCount(2);
  await expect(
    page.getByText("Inviting people while on the unlimited plan"),
  ).toBeVisible();
});

test("Invitations tab has warning message when user has more seats than editors", async ({
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

  await DashboardPage.mockRPC(
    page,
    "get-team-info",
    "subscription/get-team-info-subscriptions.json",
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

  await DashboardPage.mockRPC(
    page,
    "get-team-invitations?team-id=*",
    "subscription/get-team-invitations.json",
  );

  await dashboardPage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await dashboardPage.goToSecondTeamInvitationsSection();

  const ctas = page.getByTestId("cta");
  await expect(ctas).toHaveCount(2);
  await expect(
    page.getByText("Inviting people while on the unlimited plan"),
  ).toBeVisible();
});
