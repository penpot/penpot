import { test, expect } from "@playwright/test";
import { RegisterPage } from "../pages/RegisterPage";

// A freshly verified account has no saved theme preference. It must follow
// the system theme and show that preference in settings, not a blank field.

test.beforeEach(async ({ page }) => {
  await RegisterPage.initWithLoggedOutUser(page);
});

test.describe("Email verification", () => {
  for (const colorScheme of ["light", "dark"]) {
    test(`Newly verified account follows the ${colorScheme} system theme`, async ({
      page,
    }) => {
      await page.emulateMedia({ colorScheme });
      const registerPage = new RegisterPage(page);
      await registerPage.setupEmailVerificationSuccess();

      await registerPage.goToVerifyToken();
      await page.waitForURL(/screen=dashboard/);

      // The dark theme uses the legacy body class "default".
      await expect(page.locator("body")).toHaveClass(
        colorScheme === "dark" ? "default" : "light",
      );
    });
  }

  test("Settings > UI Theme shows System theme selected, not blank", async ({
    page,
  }) => {
    const registerPage = new RegisterPage(page);
    await registerPage.setupEmailVerificationSuccess();

    await registerPage.goToVerifyToken();
    await page.waitForURL(/screen=dashboard/);

    await page.goto("/?screen=settings-options");

    // The language select is the first combobox on the page, the theme
    // select is the second one.
    const themeSelect = page.getByRole("combobox").nth(1);
    await expect(themeSelect).toHaveText("System theme");
  });
});
