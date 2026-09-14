import { test, expect } from "@playwright/test";
import { RegisterPage } from "../pages/RegisterPage";

test.beforeEach(async ({ page }) => {
  await RegisterPage.initWithLoggedOutUser(page);
  await page.goto("/#/auth/register");
});

test.describe("Register form errors", () => {
  test("User gets error message when email does not match invitation", async ({
    page,
  }) => {
    const registerPage = new RegisterPage(page);
    await registerPage.setupMismatchedEmailError();

    await registerPage.fillRegisterFormInputs(
      "John Doe",
      "john.doe@example.com",
      "password123",
    );
    await registerPage.clickRegisterButton();

    await expect(
      page.getByText("Email does not match the invitation."),
    ).toBeVisible();
  });

  test("User gets the already used email error on the email input", async ({
    page,
  }) => {
    const registerPage = new RegisterPage(page);
    await registerPage.setupEmailAlreadyExistsError();

    await registerPage.fillRegisterFormInputs(
      "John Doe",
      "john.doe@example.com",
      "password123",
    );
    await registerPage.clickRegisterButton();

    await expect(page.getByTestId("email-input-error")).toHaveText(
      "Email already used",
    );
    await expect(page.getByRole("alert")).toHaveCount(0);
  });
});
