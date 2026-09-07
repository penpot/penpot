import { BasePage } from "./BasePage";

export class RegisterPage extends BasePage {
  constructor(page) {
    super(page);
    this.registerButton = page.getByRole("button", {
      name: "Create an account",
    });
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

  /**
   * Mocks a successful email-verification token exchange (the link the
   * user clicks from the verification email) and every RPC the dashboard
   * needs to render right after landing on it, so the flow can be
   * exercised end-to-end without a real backend.
   */
  async setupEmailVerificationSuccess() {
    await this.mockConfigFlags(["disable-onboarding"]);
    await this.mockRPC(
      "verify-token",
      "register/verify-token-email-verified.json",
    );
    await this.mockRPCs({
      "get-teams": "logged-in-user/get-teams-default.json",
      "get-font-variants?team-id=*":
        "logged-in-user/get-font-variants-empty.json",
      "get-projects?team-id=*": "logged-in-user/get-projects-default.json",
      "get-team-members?team-id=*":
        "logged-in-user/get-team-members-your-penpot.json",
      "get-team-users?team-id=*":
        "logged-in-user/get-team-users-single-user.json",
      "get-unread-comment-threads?team-id=*":
        "logged-in-user/get-team-users-single-user.json",
      "get-team-recent-files?team-id=*":
        "logged-in-user/get-team-recent-files-empty.json",
      "get-profiles-for-file-comments":
        "logged-in-user/get-profiles-for-file-comments-empty.json",
      "get-builtin-templates":
        "logged-in-user/get-built-in-templates-empty.json",
    });
  }

  async goToVerifyToken(token = "verify-email-token") {
    await this.page.goto(`/#/auth/verify-token?token=${token}`);
  }

  static async init(page) {
    await BasePage.init(page);
  }

  static async initWithLoggedOutUser(page) {
    await BasePage.init(page);
    await BasePage.mockRPC(page, "get-profile", "get-profile-anonymous.json");
  }
}

export default RegisterPage;
