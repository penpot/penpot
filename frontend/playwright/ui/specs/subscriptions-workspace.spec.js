import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// The stored-days rule and the plan the menu reads are pure functions with
// unit tests of their own. These specs prove the workspace passes the
// computed days into the translated warning and gates the upsell entry.

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

test.describe("Subscriptions: workspace", () => {
  test("An unlimited team keeps 30 days of autosaved versions", async ({
    page,
  }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WasmWorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    await WasmWorkspacePage.mockRPC(
      page,
      "get-subscription-usage",
      "subscription/get-subscription-usage.json",
    );

    await WasmWorkspacePage.mockRPC(
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

    await test.step("the main menu offers the plan upsell", async () => {
      await page.getByRole("button", { name: "Main menu" }).click();
      await expect(
        page.getByRole("menuitem", { name: "Power up your plan" }),
      ).toBeVisible();
    });
  });

  test("An enterprise team has no plan upsell in the main menu", async ({
    page,
  }) => {
    const workspacePage = new WasmWorkspacePage(page);
    await workspacePage.setupEmptyFile();

    await WasmWorkspacePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-subscription.json",
    );

    await WasmWorkspacePage.mockRPC(
      page,
      "get-subscription-usage",
      "subscription/get-subscription-usage.json",
    );

    await workspacePage.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );
    await workspacePage.goToWorkspace();

    await page.getByRole("button", { name: "Main menu" }).click();

    // The menu is open, so the absence below means something.
    await expect(page.getByTestId("help-info")).toBeVisible();
    await expect(
      page.getByRole("menuitem", { name: "Power up your plan" }),
    ).toHaveCount(0);
  });
});
