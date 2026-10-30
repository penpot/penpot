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

test.describe("Login dialog section links", () => {
  const shot = { maxDiffPixelRatio: 0, threshold: 0 };

  // The links switch the dialog section: the tests use the keyboard to
  // check they work as buttons.
  test("Login and register modal", async ({ page }) => {
    // The viewer and the MCP authorize page share this modal; the MCP page
    // opens it on a 401, with no file to load.
    await LoginPage.mockConfigFlags(page, ["enable-registration"]);
    await page.route("**/api/mcp/oauth/request?*", (route) =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        body: "{}",
      }),
    );
    await page.goto("/?screen=mcp-authorize&request=test");

    const modal = page.getByRole("dialog");
    const forgot = modal.getByText("Forgot password?", { exact: true });
    await expect(forgot).toBeVisible();
    await page.mouse.move(10, 10);
    await expect(forgot).toHaveScreenshot(shot);
    await forgot.hover();
    await expect(forgot).toHaveScreenshot(shot);

    const create = modal.getByText("Create an account", { exact: true });
    await create.hover();
    await expect(create).toHaveScreenshot(shot);

    await create.focus();
    await page.keyboard.press("Enter");
    const loginHere = modal.getByText("Login here.", { exact: true });
    await expect(loginHere).toBeVisible();
    await page.mouse.move(10, 10);
    await expect(loginHere).toHaveScreenshot(shot);
    await loginHere.hover();
    await expect(loginHere).toHaveScreenshot(shot);

    await loginHere.focus();
    await page.keyboard.press("Enter");
    await page.mouse.move(10, 10);
    await expect(modal).toHaveScreenshot(shot);
  });

  test("Not found page dialog", async ({ page }) => {
    await LoginPage.mockConfigFlags(page, ["enable-registration"]);
    // An invalid team id is a not found error; logged out users get the
    // login dialog on top of it.
    await page.goto("/?screen=dashboard-recent&team-id=not-a-uuid");

    const create = page.getByText("Create an account", { exact: true });
    await expect(create).toBeVisible();
    await page.mouse.move(10, 10);
    await expect(create).toHaveScreenshot(shot);
    await create.hover();
    await expect(create).toHaveScreenshot(shot);

    await create.focus();
    await page.keyboard.press("Enter");
    const loginHere = page.getByText("Login here.", { exact: true });
    await expect(loginHere).toBeVisible();
    // The new section reuses the focused button: blur it for the rest shot.
    await loginHere.blur();
    await page.mouse.move(10, 10);
    await expect(loginHere).toHaveScreenshot(shot);
    await loginHere.hover();
    await expect(loginHere).toHaveScreenshot(shot);
  });
});
