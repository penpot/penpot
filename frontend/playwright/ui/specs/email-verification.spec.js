import { test, expect } from "@playwright/test";
import { RegisterPage } from "../pages/RegisterPage";

// A freshly verified account has no saved theme preference. It must follow
// the system theme and show that preference in settings, not a blank field.

test.beforeEach(async ({ page }) => {
  await RegisterPage.initWithLoggedOutUser(page);
});

test("Verified account follows the system theme and shows it in settings", async ({
  page,
}) => {
  const registerPage = new RegisterPage(page);

  await test.step("follows the dark system theme", async () => {
    await page.emulateMedia({ colorScheme: "dark" });
    await registerPage.setupEmailVerificationSuccess();

    await registerPage.goToVerifyToken();
    await page.waitForURL(/screen=dashboard/);

    // The dark theme uses the legacy body class "default".
    await expect(page.locator("body")).toHaveClass("default");
  });

  await test.step("follows a change to the light system theme", async () => {
    await page.emulateMedia({ colorScheme: "light" });

    await expect(page.locator("body")).toHaveClass("light");
  });

  await test.step("shows the System theme in settings", async () => {
    await page.goto("/?screen=settings-options");

    // The language select is the first combobox on the page, the theme
    // select is the second one.
    const themeSelect = page.getByRole("combobox").nth(1);
    await expect(themeSelect).toHaveText("System theme");
  });
});
