import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// The stored-days rule is unit-tested in
// frontend/test/frontend_tests/data/workspace_versions_test.cljs. This spec
// keeps one case to prove the versions panel passes the computed days into
// the translated warning.

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
  });
});
