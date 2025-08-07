import { BasePage } from "./BasePage";

export class RegisterPage extends BasePage {
  constructor(page) {
    super(page);
    this.registerButton = page.getByRole("button", { name: "Create an account" });
    this.password = page.getByLabel("Password");
    this.email = page.getByLabel("Work email");
    this.fullName = page.getByLabel("Full name");
  }

  async fillRegisterFormInputs(name, email, password) {
    await this.fullName.fill(name);
    await this.email.fill(email);
    await this.password.fill(password);
  }

  async clickRegisterButton() {
    await this.registerButton.click();
  }

  async setupMismatchedEmailError() {
    await this.mockRPC(
      "prepare-register-profile",
      "register/prepare-register-profile-email-mismatch.json",
      { status: 400 },
    );
  }

  static async initWithLoggedOutUser(page) {
    await this.mockRPC(page, "get-profile", "get-profile-anonymous.json");
  }
}

export default RegisterPage;
