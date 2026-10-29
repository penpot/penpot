import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

test.beforeEach(async ({ page }) => {
  await LoginPage.init(page);

  const login = new LoginPage(page);
  await login.initWithLoggedOutUser();
  await login.page.goto("/?screen=auth-login");
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
    await expect(login.page).toHaveURL(/screen=auth-login$/);
    await expect(login.page).toHaveScreenshot();
  });
});

test.describe("Password recovery page", () => {
  test("Shows the Go to login link", async ({ page }) => {
    const shot = { maxDiffPixelRatio: 0, threshold: 0 };
    await page.goto("/?screen=auth-recovery&token=fake-token");

    const link = page.getByText("Go to login", { exact: true });
    await expect(link).toBeVisible();
    await page.mouse.move(10, 10);
    await expect(link).toHaveScreenshot(shot);

    await link.hover();
    await expect(link).toHaveScreenshot(shot);
  });
});
