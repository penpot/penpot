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

test("Legacy auth hash URL redirects to query-string format", async ({
  page,
}) => {
  const loginPage = new LoginPage(page);

  await page.goto("/#/auth/login");

  await expect(page).toHaveURL(/screen=auth-login/);
  expect(new URL(page.url()).hash).toBe("");
  await expect(loginPage.initialHeading).toBeVisible();
});

test("Unknown legacy hash falls through to the query flow", async ({
  page,
}) => {
  const loginPage = new LoginPage(page);
  // The unknown-route fallback rechecks profile AND teams.
  await loginPage.mockRPC("get-teams", "logged-in-user/get-teams-default.json");

  await page.goto("/?template=foo#/nope");

  await expect(loginPage.initialHeading).toBeVisible();
});
