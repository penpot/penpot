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
