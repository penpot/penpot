import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
});

test("User goes to an empty dashboard", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await expect(dashboardPage.page).toHaveURL(/screen=dashboard/);

  await expect(dashboardPage.mainHeading).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

// Empty dashboard pages

test("User goes to an empty draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDraftsEmpty();

  await dashboardPage.goToDashboard();
  await dashboardPage.draftsLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Drafts");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty fonts page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.fontsLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Fonts");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty libraries page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupLibrariesEmpty();

  await dashboardPage.goToDashboard();
  await dashboardPage.librariesLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Libraries");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty search page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupSearchEmpty();

  await dashboardPage.goToSearch();

  await expect(dashboardPage.mainHeading).toHaveText("Search results");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to the dashboard with a new project", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupNewProject();

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.projectName).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

// Dashboard pages with content

test("User goes to a full dashboard", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a full draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.draftsLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Drafts");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a full library page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.librariesLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Libraries");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a full fonts page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.fontsLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Fonts");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a full search page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToSearch();
  await expect(dashboardPage.searchInput).toBeVisible();

  await dashboardPage.searchInput.fill("3");

  await expect(dashboardPage.mainHeading).toHaveText("Search results");
  await expect(page.getByRole("button", { name: "New File 3" })).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

// Account management

test("User opens user account", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await expect(dashboardPage.userAccount).toBeVisible();
  await dashboardPage.goToAccount();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to user profile", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.goToAccount();

  await expect(dashboardPage.mainHeading).toHaveText("Your account");
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to password management section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.goToAccount();

  await page.getByText("Password").click();

  await expect(
    page.getByRole("heading", { name: "Change Password" }),
  ).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to settings section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.goToAccount();

  await page.getByTestId("settings-profile").click();

  await expect(page.getByRole("heading", { name: "Settings" })).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

// Teams management

test("User opens teams selector with only one team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.teamDropdown.click();

  await expect(page.getByText("Create new team")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User opens teams selector with more than one team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.teamDropdown.click();

  await expect(page.getByText("Second Team")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to second team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.mockRPC(
    `get-projects?team-id=${DashboardPage.secondTeamId}`,
    "dashboard/get-projects-second-team.json",
  );
  await dashboardPage.goToDashboard();

  await dashboardPage.teamDropdown.click();
  await expect(page.getByText("Second Team")).toBeVisible();

  await page.getByText("Second Team").click();

  await expect(page.getByText("Team Up")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User opens team management dropdown", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.mockRPC(
    `get-projects?team-id=${DashboardPage.secondTeamId}`,
    "dashboard/get-projects-second-team.json",
  );

  await dashboardPage.goToSecondTeamDashboard();
  await expect(page.getByText("Team Up")).toBeVisible();

  await page.getByTestId("team-options-button").click();

  await expect(page.getByTestId("team-members")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to team management section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToSecondTeamMembersSection();

  await expect(page.getByText("role")).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty invitations section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await dashboardPage.goToSecondTeamInvitationsSection();

  await expect(page.getByText("No pending invitations")).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a complete invitations section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitations();

  await dashboardPage.goToSecondTeamInvitationsSection();

  await expect(page.getByText("test1@mail.com")).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User invite people to the team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await dashboardPage.goToSecondTeamInvitationsSection();
  await expect(page.getByTestId("invite-member")).toBeVisible();

  await page.getByTestId("invite-member").click();
  await expect(page.getByText("Invite with the role")).toBeVisible();

  await page.getByPlaceholder("Emails, comma separated").fill("test5@mail.com");

  await expect(page.getByText("Send invitation")).not.toBeDisabled();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty webhook section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamWebhooksEmpty();

  await dashboardPage.goToSecondTeamWebhooksSection();

  await expect(page.getByText("No webhooks created so far.")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a complete webhook section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamWebhooks();

  await dashboardPage.goToSecondTeamWebhooksSection();

  await expect(page.getByText("https://www.google.com")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to the team settings section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamSettings();

  await dashboardPage.goToSecondTeamSettingsSection();

  await expect(page.getByText("TEAM INFO")).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test.describe("Delete account modal", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  test("Delete account modal buttons", async ({ page }) => {
    await page.goto("/?screen=settings-profile");
    await page.getByText("Want to remove your account?").click();

    const modal = page.locator("[class*='modal-container']");
    await expect(page.getByTestId("delete-account-btn")).toBeVisible();
    await page.mouse.move(10, 700);
    await expect(modal).toHaveScreenshot(shot);

    await modal.locator("[class*='modal-header'] button").hover();
    await expect(modal).toHaveScreenshot(shot);

    await page.getByTestId("delete-account-btn").hover();
    await expect(modal).toHaveScreenshot(shot);
  });
});

test.describe("Subscription controls", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  // An unlimited trial that the logged-in user owns, as in
  // specs/subscriptions-dashboard.spec.js.
  const setupSubscription = async (
    page,
    members,
    secondTeam = true,
    usage = "subscription/get-subscription-usage.json",
  ) => {
    await DashboardPage.mockConfigFlags(page, [
      "enable-subscriptions",
      "disable-onboarding",
    ]);
    await DashboardPage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );
    await DashboardPage.mockRPC(page, "get-subscription-usage", usage);
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
    if (secondTeam) {
      await DashboardPage.mockRPC(
        page,
        "get-projects?team-id=*",
        "dashboard/get-projects-second-team.json",
      );
    }
    await DashboardPage.mockRPC(page, "get-team-members?team-id=*", members);
    await DashboardPage.mockRPC(
      page,
      "get-team-stats?team-id=*",
      "dashboard/get-team-stats.json",
    );
    await dashboardPage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    return dashboardPage;
  };

  test("Sidebar plan card", async ({ page }) => {
    const dashboardPage = await setupSubscription(
      page,
      "subscription/get-team-members-subscription-owner.json",
      false,
      "subscription/get-subscription-usage-one-editor.json",
    );
    await dashboardPage.goToDashboard();

    const name = page.getByTestId("subscription-name");
    await expect(name).toHaveText("Unlimited plan (trial)");
    const card = page.locator("[class*='cta-power-up']").first();
    await page.mouse.move(640, 300);
    await expect(card).toHaveScreenshot(shot);

    await name.click();
    await page.mouse.move(640, 300);
    await expect(card).toHaveScreenshot(shot);
  });

  test("Team settings plan", async ({ page }) => {
    const dashboardPage = await setupSubscription(
      page,
      "subscription/get-team-members-subscription-owner.json",
    );
    await dashboardPage.goToSecondTeamSettingsSection();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();
    await expect(
      page.locator("[class$='subscription__team']"),
    ).toHaveScreenshot(shot);
  });

  test("Members seats warning", async ({ page }) => {
    const dashboardPage = await setupSubscription(
      page,
      "subscription/get-team-members-subscription-eight-member.json",
    );
    await dashboardPage.goToSecondTeamMembersSection();

    await expect(page.getByTestId("cta")).toHaveCount(2);
    await page.mouse.move(640, 700);
    await expect(page).toHaveScreenshot(shot);
  });
});

test.describe("Team modals controls", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  test("Create team modal", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.goToDashboard();
    await dashboardPage.teamDropdown.click();
    await page.getByText("Create new team").click();

    const modal = page.locator("[class*='modal-container']");
    await expect(modal).toBeVisible();
    await page.mouse.move(10, 700);
    await expect(modal).toHaveScreenshot(shot);

    await modal.locator("[class*='modal-header'] button").hover();
    await expect(modal).toHaveScreenshot(shot);
  });

  test("Leave and reassign modal", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    // The logged-in user owns the second team, which has another member.
    await dashboardPage.mockRPC(
      "get-teams",
      "dashboard/get-teams-second-owner.json",
    );
    await dashboardPage.mockRPC(
      "get-team-members?team-id=*",
      "dashboard/get-team-members-owner.json",
    );
    await dashboardPage.goToSecondTeamMembersSection();
    await expect(page.getByText("role")).toBeVisible();

    const rows = page.locator("[class*='table-rows']");
    await rows
      .locator("[class*='menu-btn']")
      .or(rows.getByRole("button", { name: "Options" }))
      .first()
      .click();
    await rows.getByText("Leave team").click();

    const modal = page.locator("[class*='modal-container']");
    await expect(modal).toBeVisible();
    await page.mouse.move(10, 700);
    await expect(modal).toHaveScreenshot(shot);
  });
});

test.describe("Dashboard sidebar controls", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  test("Search box, pinned placeholder and profile menu", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.goToDashboard();

    const search = page.locator("[class*='sidebar-search']");
    await expect(search).toBeVisible();
    await expect(search).toHaveScreenshot(shot);

    await expect(page.getByTestId("pinned-projects")).toHaveScreenshot(shot);

    // A search term swaps the search icon for the clear button.
    await page.locator("#search-input").fill("test");
    await expect(page).toHaveURL(/search/);
    const clear = search.locator("button");
    await clear.hover();
    await expect(search).toHaveScreenshot(shot);

    await page.getByTestId("profile-btn").click();
    await expect(page.getByTestId("logout-profile-opt")).toBeVisible();
    await expect(
      page.getByTestId("logout-profile-opt").locator("xpath=.."),
    ).toHaveScreenshot(shot);
  });
});

test.describe("Team pages controls", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  // The row menu button: a raw button with the menu-btn class before the DS
  // migration, a DS icon button named "Options" after it.
  const openRowMenu = (page) => {
    const rows = page.locator("[class*='table-rows']");
    return rows
      .locator("[class*='menu-btn']")
      .or(rows.getByRole("button", { name: "Options" }))
      .first()
      .click();
  };

  test("Members role and actions menus", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    // Han Solo owns the team; the logged-in user is an admin.
    await dashboardPage.mockRPC(
      "get-team-members?team-id=*",
      "dashboard/get-team-members-admin.json",
    );
    await dashboardPage.goToSecondTeamMembersSection();
    await expect(page.getByText("role")).toBeVisible();

    await page.locator("[class*='has-priv']").first().click();
    await expect(page.locator("[class*='roles-dropdown']")).toBeVisible();
    await expect(page).toHaveScreenshot(shot);

    await page.keyboard.press("Escape");
    await page.mouse.click(10, 700);
    await openRowMenu(page);
    await expect(page.locator("[class*='actions-dropdown']")).toBeVisible();
    await expect(page).toHaveScreenshot(shot);
  });

  test("Invitations role menu and resend modal", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    await dashboardPage.setupTeamInvitations();
    await dashboardPage.goToSecondTeamInvitationsSection();
    await expect(page.getByText("test1@mail.com")).toBeVisible();

    await page.locator("[class*='has-priv']").first().click();
    await expect(page.locator("[class*='roles-dropdown']")).toBeVisible();
    await expect(page).toHaveScreenshot(shot);

    await page.mouse.click(10, 700);
    await page.getByText("test1@mail.com").click();
    await page.getByRole("button", { name: "Resend invitation" }).click();
    await expect(page.getByText("Resend invitations")).toBeVisible();
    await page.mouse.move(10, 700);
    await expect(page).toHaveScreenshot(shot);
  });

  test("Webhooks actions menu and modal", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    await dashboardPage.setupTeamWebhooks();
    await dashboardPage.goToSecondTeamWebhooksSection();
    await expect(page.getByText("https://www.google.com")).toBeVisible();

    await openRowMenu(page);
    await expect(
      page.locator("[class*='webhook-actions-dropdown']"),
    ).toBeVisible();
    await expect(page).toHaveScreenshot(shot);

    await page.mouse.click(10, 700);
    await page.getByText("Create webhook").click();
    await expect(page.getByText("Payload URL")).toBeVisible();
    await page.mouse.move(10, 700);
    await expect(page).toHaveScreenshot(shot);
  });

  test("Team settings image overlay", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    await dashboardPage.setupTeamSettings();
    await dashboardPage.goToSecondTeamSettingsSection();
    await expect(page.getByText("TEAM INFO")).toBeVisible();

    await page.locator("[class*='team-icon']").hover();
    await expect(page.locator("[class*='team-icon']")).toHaveScreenshot(shot);
  });
});
