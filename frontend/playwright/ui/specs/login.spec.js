import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

test.beforeEach(async ({ page }) => {
  const login = new LoginPage(page);
  await login.initWithLoggedOutUser();

  await page.goto("/#/auth/login");
});

test("User is redirected to the login page when logged out", async ({
  page,
}) => {
  const loginPage = new LoginPage(page);

  await loginPage.setupLoggedInUser();

  await expect(loginPage.page).toHaveURL(/auth\/login$/);
  await expect(loginPage.initialHeading).toBeVisible();
});

test.describe("Login form", () => {
  test("User logs in by filling the login form", async ({ page }) => {
    const loginPage = new LoginPage(page);
    await loginPage.setupLoginSuccess();
    await loginPage.setupLoggedInUser();

    await loginPage.fillEmailAndPasswordInputs("foo@example.com", "loremipsum");
    await loginPage.clickLoginButton();

    await page.waitForURL("**/dashboard/**");
    await expect(loginPage.page).toHaveURL(/dashboard/);
  });

  test("User gets error message when submitting an bad formatted email ", async ({
    page,
  }) => {
    const loginPage = new LoginPage(page);
    await loginPage.setupLoginSuccess();

    await loginPage.fillEmailAndPasswordInputs("foo", "lorenIpsum");

    await expect(loginPage.invalidEmailError).toBeVisible();
  });

  test("User gets error message when submitting wrong credentials", async ({
    page,
  }) => {
    const loginPage = new LoginPage(page);
    await loginPage.setupLoginError();

    await loginPage.fillEmailAndPasswordInputs(
      "test@example.com",
      "loremipsum",
    );
    await loginPage.clickLoginButton();

    await expect(loginPage.invalidCredentialsError).toBeVisible();
    await expect(loginPage.page).toHaveURL(/auth\/login$/);
  });
});
