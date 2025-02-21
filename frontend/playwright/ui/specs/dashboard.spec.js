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

test("Dashboad page has title ", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);

  await dashboardPage.goToDashboard();

  await expect(dashboardPage.page).toHaveURL(/dashboard/);
  await expect(dashboardPage.mainHeading).toBeVisible();
});

test("User can create a new project", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupNewProject();

  await dashboardPage.goToDashboard();
  await dashboardPage.addProjectButton.click();

  await expect(dashboardPage.projectName).toBeVisible();
});

test("User goes to draft page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDraftsEmpty();

  await dashboardPage.goToDashboard();
  await dashboardPage.draftsLink.click();

  await expect(dashboardPage.mainHeading).toHaveText("Drafts");
});

test("Lists files in the drafts page", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();

  await dashboardPage.goToDrafts();

  await expect(
    dashboardPage.page.getByRole("button", { name: /New File 1/ }),
  ).toBeVisible();
  await expect(
    dashboardPage.page.getByRole("button", { name: /New File 2/ }),
  ).toBeVisible();
});

test("User hasn't an empty placeholder", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();
  await expect(
    dashboardPage.page.getByTestId("empty-placeholder"),
  ).toBeHidden();
});

test("User has context menu options for edit file", async ({ page }) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();

  const button = dashboardPage.page.getByRole("button", { name: /New File 2/ });
  await button.click();
  await button.click({ button: "right" });

  await expect(dashboardPage.page.getByText("rename")).toBeVisible();
  await expect(dashboardPage.page.getByText("duplicate")).toBeVisible();
  await expect(
    dashboardPage.page.getByText("add as shared library"),
  ).toBeVisible();
  await expect(dashboardPage.page.getByText("delete")).toBeVisible();
});

test("Multiple elements in context", async ({ page }) => {
  await DashboardPage.mockRPC(
    page,
    "get-all-projects",
    "dashboard/get-all-projects.json",
  );

  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();

  const button = dashboardPage.page.getByRole("button", { name: /New File 1/ });
  await button.click();

  const button2 = dashboardPage.page.getByRole("button", {
    name: /New File 2/,
  });
  await button2.click({ modifiers: ["Shift"] });

  await button.click({ button: "right" });

  await expect(page.getByTestId("duplicate-multi")).toBeVisible();
  await expect(page.getByTestId("file-move-multi")).toBeVisible();
  await expect(page.getByTestId("file-binary-export-multi")).toBeVisible();
  await expect(page.getByTestId("file-delete-multi")).toBeVisible();
});

test("User has create file button", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDrafts();
  await dashboardPage.goToDrafts();
  await expect(dashboardPage.page.getByText("+ New File")).toBeVisible();
});

test("User has add font button", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToFonts();
  await expect(dashboardPage.page.getByText("add custom font")).toBeVisible();
});

test("Bug 9443, Admin can not demote owner", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-team-members?team-id=*",
    "dashboard/get-team-members-admin.json",
  );

  await dashboardPage.goToSecondTeamMembersSection();

  await expect(page.getByRole("heading", { name: "Members" })).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Admin" })).toBeVisible();
  await expect(page.getByText("Owner")).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Owner" })).toHaveCount(0);
});

test("Bug 9927, Don't show the banner to invite team members if the user has dismissed it", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-projects?team-id=*",
    "dashboard/get-projects-second-team.json",
  );
  await dashboardPage.goToSecondTeamDashboard();
  await expect(page.getByText("Team Up")).toBeVisible();
  await page.getByRole("button", { name: "Close" }).click();
  await page.reload();
  await expect(page.getByText("Second team")).toBeVisible();
  await expect(page.getByText("Team Up")).not.toBeVisible();
});

test("Bug 10141, The team does not disappear from the team list after deletion", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "logged-in-user/get-teams-complete-owner.json",
  );
  await dashboardPage.goToDashboard();
  await dashboardPage.teamDropdown.click();
  await expect(page.getByText("Second Team")).toBeVisible();
  await page.getByText("Second Team").click();
  await page.getByRole("button", { name: "team-management" }).click();
  await page.getByTestId("delete-team").click();

  await DashboardPage.mockRPC(
    page,
    "get-teams",
    "logged-in-user/get-teams-default.json",
  );

  await page.getByRole("button", { name: "Delete team" }).click();
  await dashboardPage.teamDropdown.click();
  await expect(page.getByText("Second Team")).not.toBeVisible();
});
