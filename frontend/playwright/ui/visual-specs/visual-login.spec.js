import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

test.beforeEach(async ({ page }) => {
  const login = new LoginPage(page);
  await login.initWithLoggedOutUser();
  await login.page.goto("/#/auth/login");
});

test.describe("Login form", () => {
  test("Shows the login form correctly", async ({ page }) => {
    const login = new LoginPage(page);
    await expect(login.page).toHaveScreenshot();
  });

  test("Shows form error messages correctly ", async ({ page }) => {
    const login = new LoginPage(page);
    await login.setupLoginSuccess();

    await login.fillEmailAndPasswordInputs("foo", "lorenIpsum");

    await expect(login.invalidEmailError).toBeVisible();
    await expect(login.page).toHaveScreenshot();
  });

  test("Shows error toasts correctly", async ({ page }) => {
    const login = new LoginPage(page);
    await login.setupLoginError();

    await login.fillEmailAndPasswordInputs("test@example.com", "loremipsum");
    await login.clickLoginButton();

    await expect(login.invalidCredentialsError).toBeVisible();
    await expect(login.page).toHaveURL(/auth\/login$/);
    await expect(login.page).toHaveScreenshot();
  });
});
