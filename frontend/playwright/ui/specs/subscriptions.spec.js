import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";
import { WorkspacePage } from "../pages/WorkspacePage";
import { SubscriptionProfilePage } from "../pages/SubscriptionProfilePage";

test.describe("Subscriptions: dashboard", () => {
  test("Team with unlimited subscription has specific icon in menu", async ({
    page,
  }) => {
    await DashboardPage.init(page);
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
    await DashboardPage.init(page);
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
});

test.describe("Subscriptions: Team members and invitations", () => {
  test("Team settings has susbscription name and no manage subscription link when is member", async ({
    page,
  }) => {
    await DashboardPage.init(page);
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
    await DashboardPage.init(page);
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
    await DashboardPage.init(page);
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
    await DashboardPage.init(page);
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
    await DashboardPage.init(page);
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

  test("Invitations tab has warning message when subscription is expired", async ({
    page,
  }) => {
    await DashboardPage.init(page);
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
      "subscription/get-teams-unlimited-subscription-expired-owner.json",
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
        "Looks like your team has grown! Your plan includes seats, but you're now using more than that.",
      ),
    ).toBeVisible();
  });

  test("Invitations tab has warning message when has professional subscription and more than 8 members.", async ({
    page,
  }) => {
    await DashboardPage.init(page);
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
      "subscription/get-teams-unlimited-subscription-expired-owner.json",
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
        "Looks like your team has grown! Your plan includes seats, but you're now using more than that.",
      ),
    ).toBeVisible();
  });
});

test.describe("Subscriptions: workspace", () => {
  test("Unlimited team should have 'Power up your plan' link in main menu", async ({
    page,
  }) => {
    await WorkspacePage.init(page);

    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();
    await page.getByRole("button", { name: "Main menu" }).click();

    await expect(page.getByText("Power up your plan")).toBeVisible();
  });

  test("Enterprise team should not have 'Power up your plan' link in main menu", async ({
    page,
  }) => {
    await WorkspacePage.init(page);

    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-subscription.json",
    );

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();
    await page.getByRole("button", { name: "Main menu" }).click();

    await expect(page.getByText("Power up your plan")).not.toBeVisible();
  });

  test("Professional team should have 7 days autosaved versions", async ({
    page,
  }) => {
    await WorkspacePage.init(page);
    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();

    await workspacePage.mockRPC(
      "get-file-snapshots?file-id=*",
      "workspace/versions-snapshot-1.json",
    );

    await page.getByLabel("History").click();

    await expect(
      page.getByText("Autosaved versions will be kept for 7 days."),
    ).toBeVisible();
  });

  test("Unlimited team should have 30 days autosaved versions", async ({
    page,
  }) => {
    await WorkspacePage.init(page);
    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await WorkspacePage.mockRPC(
      page,
      "get-teams",
      "subscription/get-teams-unlimited-one-team.json",
    );

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();

    await workspacePage.mockRPC(
      "get-file-snapshots?file-id=*",
      "workspace/versions-snapshot-1.json",
    );

    await page.getByLabel("History").click();

    await expect(
      page.getByText("Autosaved versions will be kept for 30 days."),
    ).toBeVisible();
  });

  test("Unlimited team should have 90 days autosaved versions", async ({
    page,
  }) => {
    await WorkspacePage.init(page);
    const workspacePage = new WorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-subscription.json",
    );

    await WorkspacePage.mockRPC(
      page,
      "get-teams",
      "subscription/get-teams-enterprise-one-team.json",
    );

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();

    await workspacePage.mockRPC(
      "get-file-snapshots?file-id=*",
      "workspace/versions-snapshot-1.json",
    );

    await page.getByLabel("History").click();

    await expect(
      page.getByText("Autosaved versions will be kept for 90 days."),
    ).toBeVisible();
  });
});

test.describe("Subscriptions: profile", () => {
  test("When subscription is professional there is no manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.init(page);
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).not.toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$7")).toBeVisible();

    await expect(page.getByText("$950")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).toBeVisible();
  });

  test("When subscription is unlimited there is manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.init(page);
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$0")).toBeVisible();

    await expect(page.getByText("$950")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).not.toBeVisible();

    await expect(
      page.getByRole("button", { name: "Subscribe" }).first(),
    ).toBeVisible();
  });

  test("When subscription is enteprise there is manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.init(page);
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-subscription.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$0")).toBeVisible();

    await expect(page.getByText("$7")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).not.toBeVisible();

    await expect(
      page.getByRole("button", { name: "Subscribe" }).first(),
    ).toBeVisible();
  });
});
