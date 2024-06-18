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

test("User goes to an empty dashboard", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.titleLabel).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

// Empty dashboard pages

test("User goes to an empty draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDraftsEmpty();

  await dashboardPage.goToDashboard();
  await dashboardPage.draftLink.click();

  await expect(dashboardPage.draftTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty fonts page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();
  await dashboardPage.fontsLink.click();

  await expect(dashboardPage.fontsTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty libraries page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupLibrariesEmpty();

  await dashboardPage.goToDashboard();
  await dashboardPage.libsLink.click();

  await expect(dashboardPage.libsTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an empty search page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupSearchEmpty();

  await dashboardPage.goToSearch();

  await expect(dashboardPage.searchTitle).toBeVisible();
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

  await expect(dashboardPage.draftsFile).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an full draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.draftLink.click();

  await expect(dashboardPage.draftTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an full library page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.libsLink.click();

  await expect(dashboardPage.libsTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an full fonts page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();
  await dashboardPage.fontsLink.click();

  await expect(dashboardPage.fontsTitle).toBeVisible();
  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to an full search page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToSearch();

  await expect(dashboardPage.searchInput).toBeVisible();

  await dashboardPage.searchInput.fill("New");

  await expect(dashboardPage.searchTitle).toBeVisible();

  await expect(dashboardPage.newFileName).toBeVisible();

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

  await expect(dashboardPage.userAccountTitle).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to password management section", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await dashboardPage.goToAccount();

  await page.getByText("Password").click();

  await expect(page.getByRole("heading", { name: "Change Password" })).toBeVisible();

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

test("User goes to an empty access tokens secction", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await dashboardPage.setupAccessTokensEmpty();

  await dashboardPage.goToAccount();

  await page.getByText("Access tokens").click();

  await expect(page.getByRole("heading", { name: "Personal access tokens" })).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User can create an access token", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await dashboardPage.setupAccessTokensEmpty();

  await dashboardPage.goToAccount();

  await page.getByText("Access tokens").click();

  await expect(page.getByRole("heading", { name: "Personal access tokens" })).toBeVisible();

  await page.getByRole("button", { name: "Generate New Token" }).click();

  await dashboardPage.createAccessToken();

  await expect(page.getByPlaceholder("The name can help to know")).toBeVisible();

  await page.getByPlaceholder("The name can help to know").fill("New token");

  await expect(page.getByRole("button", { name: "Create token" })).not.toBeDisabled();

  await page.getByRole("button", { name: "Create token" }).click();

  await expect(page.getByRole("button", { name: "Create token" })).not.toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to a full access tokens secction", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await dashboardPage.setupAccessTokens();

  await dashboardPage.goToAccount();

  await page.getByText("Access tokens").click();

  await expect(page.getByRole("heading", { name: "Personal access tokens" })).toBeVisible();

  await expect(page.getByText("new token", { exact: true })).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to the feedback secction", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await dashboardPage.goToAccount();

  await page.getByText("Give feedback").click();

  await expect(page.getByRole("heading", { name: "Email" })).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

// Teams management

test("User opens teams selector with only one team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.titleLabel).toBeVisible();

  await dashboardPage.teamDropdown.click();

  await expect(page.getByText("Create new team")).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User opens teams selector with more than one team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.titleLabel).toBeVisible();

  await dashboardPage.teamDropdown.click();

  await expect(page.getByText("Second Team")).toBeVisible();

  await expect(dashboardPage.page).toHaveScreenshot();
});

test("User goes to second team", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();

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

  await dashboardPage.goToSecondTeamDashboard();

  await expect(page.getByText("Team Up")).toBeVisible();

  await page.getByRole("button", { name: "team-management" }).click();

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

  await page.getByPlaceholder('Emails, comma separated').fill("test5@mail.com");

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
