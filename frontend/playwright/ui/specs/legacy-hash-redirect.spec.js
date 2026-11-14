import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

// One-version compatibility: legacy `#/…` hash URLs translate to the
// query-string format client-side (the fragment never reaches the
// server). TODO(next-version): delete with the legacy hash shim.
test.beforeEach(async ({ page }) => {
  await LoginPage.init(page);

  const login = new LoginPage(page);
  await login.initWithLoggedOutUser();
});

test("Legacy hash URLs translate or fall through", async ({ page }) => {
  const loginPage = new LoginPage(page);

  await test.step("a known hash translates to the query string", async () => {
    await page.goto("/#/auth/login");

    await expect(page).toHaveURL(/screen=auth-login/);
    expect(new URL(page.url()).hash).toBe("");
    await expect(loginPage.initialHeading).toBeVisible();
  });

  await test.step("an unknown hash falls through to the query flow", async () => {
    // The unknown-route fallback rechecks profile AND teams.
    await loginPage.mockRPC(
      "get-teams",
      "logged-in-user/get-teams-default.json",
    );

    await page.goto("/?template=foo#/nope");

    await expect(loginPage.initialHeading).toBeVisible();
  });
});
