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

test.describe("Dashboard list controls", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  test("Trash project options", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    // Deleted files due in 2100, so the trash keeps them.
    await dashboardPage.mockRPC(
      "get-team-deleted-files?team-id=*",
      "dashboard/get-team-deleted-files-pending.json",
    );
    // A deleted project, so its row shows the options button.
    await dashboardPage.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-deleted.json",
    );
    await dashboardPage.goToDeleted();

    const options = page.getByTestId("project-options").first();
    await expect(options).toBeVisible();
    const row = page.locator("[class*='dashboard-project-row']").first();
    await page.mouse.move(640, 700);
    await expect(row.locator("header")).toHaveScreenshot(shot);
    await options.hover();
    await expect(row.locator("header")).toHaveScreenshot(shot);
  });

  test("Empty project create button", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupNewProject();
    await dashboardPage.goToDashboard();
    await expect(dashboardPage.projectName).toBeVisible();

    const create = page.locator("[class*='create-new']").first();
    await expect(create).toBeVisible();
    await page.mouse.move(640, 700);
    await expect(create).toHaveScreenshot(shot);
    await create.hover();
    await expect(create).toHaveScreenshot(shot);
  });

  test("Project rename", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.setupDashboardFull();
    await dashboardPage.goToDashboard();

    await page.getByTestId("project-options").first().click();
    await page.getByText("Rename", { exact: true }).click();
    const edit = page.locator("[class*='edit-wrapper']").first();
    await expect(edit).toBeVisible();
    await expect(edit).toHaveScreenshot(shot);
  });

  test("Comments placeholder", async ({ page }) => {
    const dashboardPage = new DashboardPage(page);
    await dashboardPage.mockRPC(
      "get-unread-comment-threads?team-id=*",
      "logged-in-user/get-unread-comment-threads-empty.json",
    );
    await dashboardPage.goToDashboard();

    await page.getByTestId("open-comments").click();
    const placeholder = page.locator("[class*='thread-groups-placeholder']");
    await expect(placeholder).toBeVisible();
    await expect(placeholder).toHaveScreenshot(shot);
  });
});
