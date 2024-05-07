import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

test.beforeEach(async ({ page }) => {
  await LoginPage.setupLoggedOutUser(page);
  await page.goto("/#/auth/login");
});

test("Shows login page when going to index and user is logged out", async ({ page }) => {
  const loginPage = new LoginPage(page);

  await loginPage.setupAllowedUser();

  await expect(loginPage.page).toHaveURL(/auth\/login$/);
  await expect(loginPage.initialHeading).toBeVisible();
});

test("User submit a wrong formated email ", async ({ page }) => {
  const loginPage = new LoginPage(page);

  await loginPage.setupLoginSuccess();

  await loginPage.fillEmailAndPasswordInputs("foo", "lorenIpsum");

  await expect(loginPage.badLoginMsg).toBeVisible();
});

test("User logs in by filling the login form", async ({ page }) => {
  const loginPage = new LoginPage(page);

  await loginPage.setupLoginSuccess();
  await loginPage.setupAllowedUser();

  await loginPage.fillEmailAndPasswordInputs("foo@example.com", "loremipsum");
  await loginPage.clickLoginButton();

  await page.waitForURL('**/dashboard/**');
  await expect(loginPage.page).toHaveURL(/dashboard/);
});

test("User submits wrong credentials", async ({ page }) => {
  const loginPage = new LoginPage(page);

  await loginPage.setupLoginError();

  await loginPage.fillEmailAndPasswordInputs("test@example.com", "loremipsum");
  await loginPage.clickLoginButton();

  await expect(loginPage.message).toBeVisible();
  await expect(loginPage.page).toHaveURL(/auth\/login$/);
});
