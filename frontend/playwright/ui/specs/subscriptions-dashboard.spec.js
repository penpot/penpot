import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

test.describe("Subscriptions: dashboard", () => {
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

  test("When the subscription status is unpaid, the sidebar dropdown displays the name Professional for the Unlimited subscription", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-unpaid-subscription.json",
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

  test("When the subscription status is canceled, the sidebar dropdown displays the name Professional for the Enterprise subscription", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-canceled-subscription.json",
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
});

test.describe("Subscriptions: team members and invitations", () => {
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

  test("Members tab has warning message when team has more members than subscriptions. Subscribe link is shown for owners.", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
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

    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboardPage.goToSecondTeamMembersSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(page.getByText("Subscribe now.")).toBeVisible();
  });

  test("Members tab has warning message when team has more members than subscriptions. Contact to owner is shown for members.", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
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

    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboardPage.goToSecondTeamMembersSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(page.getByText("Contact with the team owner")).toBeVisible();
  });

  test("Members tab has warning message when has professional subscription and more than 8 members.", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
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
      "subscription/get-teams-professional-subscription-owner.json",
    );

    await DashboardPage.mockRPC(
      page,
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await DashboardPage.mockRPC(
      page,
      "get-team-members?team-id=*",
      "subscription/get-team-members-more-than-8.json",
    );

    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboardPage.goToSecondTeamMembersSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "The Professional plan is designed for teams of up to 8 editors (owner, admin, and editor).",
      ),
    ).toBeVisible();
  });

  test("Invitations tab has warning message when team has more members than subscriptions", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
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
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations-empty.json",
    );

    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboardPage.goToSecondTeamInvitationsSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "Looks like your team has grown! Your plan includes 2 seats, but you're now using 3",
      ),
    ).toBeVisible();
  });

  test("Invitations tab has warning message when has professional subscription and more than 8 members.", async ({
    page,
  }) => {
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
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
      "subscription/get-team-members-more-than-8.json",
    );

    await DashboardPage.mockRPC(
      page,
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations-empty.json",
    );

    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboardPage.goToSecondTeamInvitationsSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "Looks like your team has grown! Your plan includes 2 seats, but you're now using 9",
      ),
    ).toBeVisible();
  });
});
