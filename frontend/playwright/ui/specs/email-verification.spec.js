import { test, expect } from "@playwright/test";
import { RegisterPage } from "../pages/RegisterPage";

// Regression test for the bug where a freshly verified account (whose
// profile never had a theme persisted) ended up with an empty string as
// its theme instead of falling back to the dark default: the workspace
// switched to light mode and Settings > UI Theme showed a blank field.

test.beforeEach(async ({ page }) => {
  await RegisterPage.initWithLoggedOutUser(page);
});

test.describe("Email verification", () => {
  test("Newly verified account defaults to the dark theme", async ({
    page,
  }) => {
    const registerPage = new RegisterPage(page);
    await registerPage.setupEmailVerificationSuccess();

    await registerPage.goToVerifyToken();
    await page.waitForURL("**/dashboard/**");

    // `default` is the body class applied for dark theme, `light` for
    // light theme (see app.util.theme/set-color-scheme).
    await expect(page.locator("body")).toHaveClass(/default/);
    await expect(page.locator("body")).not.toHaveClass(/light/);
  });

  test("Settings > UI Theme shows Penpot Dark (default) selected, not blank", async ({
    page,
  }) => {
    const registerPage = new RegisterPage(page);
    await registerPage.setupEmailVerificationSuccess();

    await registerPage.goToVerifyToken();
    await page.waitForURL("**/dashboard/**");

    await page.goto("/#/settings/options");

    // The language select is the first combobox on the page, the theme
    // select is the second one.
    const themeSelect = page.getByRole("combobox").nth(1);
    await expect(themeSelect).toHaveText("Penpot Dark (default)");
  });
});
