import { test, expect } from "@playwright/test";
import { LoginPage } from "../pages/LoginPage";

test("Shows login form correctly", async ({ page }) => {
  await LoginPage.initWithLoggedOutUser(page);
  const loginPage = new LoginPage(page);
  await page.goto("/#/auth/login");

  await expect(page).toHaveScreenshot();
});
