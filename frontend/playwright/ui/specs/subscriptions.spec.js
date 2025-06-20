import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";
import { SubscriptionProfilePage } from "../pages/SubscriptionProfilePage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockConfigFlags(page, [
    "disable-onboarding",
    "enable-subscriptions",
  ]);
});

test.describe("Subscriptions: dashboard", () => {
  test("Team with unlimited subscription has specific icon in menu", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );
    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );
    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-owner.json",
    );
    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );
    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamDashboard();

    await expect(page.getByTestId("subscription-icon")).toBeVisible();
  });

  test("The Unlimited subscription has its name in the sidebar dropdown", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );
    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );
    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-owner.json",
    );
    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToDashboard();

    await expect(page.getByTestId("subscription-name")).toHaveText(
      "Unlimited plan (trial)",
    );
  });
});

test.describe("Subscriptions: Team members and invitations", () => {
  test("Team settings has susbscription name and no manage subscription link when is member", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);

    await dashboard.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );
    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );
    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-member.json",
    );
    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );
    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-subscription-member.json",
    );
    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamSettingsSection();

    await expect(page.getByText("Unlimited (trial)")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).not.toBeVisible();
  });

  test("Team settings has susbscription name and manage subscription link when is owner", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );
    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-owner.json",
    );
    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );
    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-subscription-owner.json",
    );
    await dashboard.mockRPC(
      "get-team-stats?team-id=*",
      "dashboard/get-team-stats.json",
    );
    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamSettingsSection();

    await expect(page.getByText("Unlimited (trial)")).toBeVisible();
    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();
  });

  test("Members tab has warning message when team has more members than subscriptions. Subscribe link is shown for owners.", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );

    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-owner.json",
    );

    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-subscription-owner.json",
    );

    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamMembersSection();
    await expect(dashboard.page.getByTestId("cta")).toBeVisible();
    await expect(dashboard.page.getByText("Subscribe now.")).toBeVisible();
  });

  test("Members tab has warning message when team has more members than subscriptions. Contact to owner is shown for members.", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);

    await dashboard.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );

    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-member.json",
    );

    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-subscription-member.json",
    );

    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamMembersSection();

    await expect(dashboard.page.getByTestId("cta")).toBeVisible();
    await expect(
      dashboard.page.getByText("Contact with the team owner"),
    ).toBeVisible();
  });

  test("Members tab has warning message when has professional subscription and more than 8 members.", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);
    await dashboard.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );

    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-professional-subscription-owner.json",
    );

    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-more-than-8.json",
    );

    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamMembersSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "The Professional plan is designed for teams of up to 8 editors (owner, admin, and editor).",
      ),
    ).toBeVisible();
  });

  test("Invitations tab has warning message when subscription is expired", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);

    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );

    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-expired-owner.json",
    );

    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-subscription-owner.json",
    );

    await dashboard.mockRPC(
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations-empty.json",
    );

    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamInvitationsSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "Looks like your team has grown! Your plan includes seats, but you're now using more than that.",
      ),
    ).toBeVisible();
  });

  test("Invitations tab has warning message when has professional subscription and more than 8 members.", async ({
    page,
  }) => {
    const dashboard = new DashboardPage(page);

    await dashboard.mockRPC(
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await dashboard.mockRPC(
      "get-team-info",
      "subscription/get-team-info-subscriptions.json",
    );

    await dashboard.setupDashboardFull();
    await dashboard.mockRPC(
      "get-teams",
      "subscription/get-teams-unlimited-subscription-expired-owner.json",
    );

    await dashboard.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-second-team.json",
    );

    await dashboard.mockRPC(
      "get-team-members?team-id=*",
      "subscription/get-team-members-more-than-8.json",
    );

    await dashboard.mockRPC(
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations-empty.json",
    );

    await dashboard.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await dashboard.goToSecondTeamInvitationsSection();
    await expect(page.getByTestId("cta")).toBeVisible();
    await expect(
      page.getByText(
        "Looks like your team has grown! Your plan includes seats, but you're now using more than that.",
      ),
    ).toBeVisible();
  });
});
