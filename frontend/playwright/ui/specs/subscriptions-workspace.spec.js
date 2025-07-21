import { test, expect } from "@playwright/test";
import WorkspacePage from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

test.describe("Subscriptions: workspace", () => {
  test("Unlimited team should have 'Power up your plan' link in main menu", async ({
    page,
  }) => {
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
