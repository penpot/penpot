import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
});

test("Open invite members modal from invitations section", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await dashboardPage.goToSecondTeamInvitationsSection();
  await expect(page.getByRole("button", { name: "Invite people" })).toBeVisible();
  await page.getByRole("button", { name: "Invite people" }).click();
  await expect(page.getByText("Invite members to the team")).toBeVisible();
});

test("Invite a new member by email", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await DashboardPage.mockRPC(
    page,
    "create-team-invitations",
    "dashboard/create-team-invitations.json",
    { method: "POST" },
  );

  await dashboardPage.goToSecondTeamInvitationsSection();
  await page.getByRole("button", { name: "Invite people" }).click();
  await expect(page.getByText("Invite members to the team")).toBeVisible();

  const emailInput = page.getByRole("textbox", { name: "Emails, comma separated" });
  await emailInput.fill("newmember@example.com");
  await emailInput.press("Enter");

  await page.getByRole("button", { name: "Send invitation" }).click();

  await expect(page.getByText("Invitation sent successfully")).toBeVisible();
  await expect(
    page.getByText("Invite members to the team"),
  ).not.toBeVisible();
});

test("Show warning when inviting an existing member", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await dashboardPage.goToSecondTeamInvitationsSection();
  await page.getByRole("button", { name: "Invite people" }).click();
  await expect(page.getByText("Invite members to the team")).toBeVisible();

  const emailInput = page.getByRole("textbox", { name: "Emails, comma separated" });
  await emailInput.fill("foo@example.com");
  await emailInput.press("Enter");

  await expect(
    page.getByText(
      "Some members are already on the team. We'll invite the rest.",
    ),
  ).toBeVisible();
});

test("Disable send button when all entered emails are existing members", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.setupDashboardFull();
  await dashboardPage.setupTeamInvitationsEmpty();

  await dashboardPage.goToSecondTeamInvitationsSection();
  await page.getByRole("button", { name: "Invite people" }).click();
  await expect(page.getByText("Invite members to the team")).toBeVisible();

  const emailInput = page.getByRole("textbox", { name: "Emails, comma separated" });
  await emailInput.fill("foo@example.com");
  await emailInput.press("Enter");

  const sendButton = page.getByRole("button", { name: "Send invitation" });
  await expect(sendButton).toBeDisabled();
});
