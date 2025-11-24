import { BasePage } from "./BasePage";

export class LoginPage extends BasePage {
  constructor(page) {
    super(page);
    this.loginButton = page.getByRole("button", { name: "Continue" });
    this.password = page.getByLabel("Password");
    this.userName = page.getByLabel("Email");
    this.invalidCredentialsError = page.getByText(
      "Email or password is incorrect",
    );
    this.invalidEmailError = page.getByText("Enter a valid email please");
    this.initialHeading = page.getByRole("heading", {
      name: "Log into my account",
    });
  }

  async fillEmailAndPasswordInputs(email, password) {
    await this.userName.fill(email);
    await this.password.fill(password);
  }

  async clickLoginButton() {
    await this.loginButton.click();
  }

  async initWithLoggedOutUser() {
    await this.mockRPC("get-profile", "get-profile-anonymous.json");
  }

  async setupLoggedInUser() {
    await this.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );
    await this.mockRPC("get-teams", "logged-in-user/get-teams-default.json");
    await this.mockRPC(
      "get-font-variants?team-id=*",
      "logged-in-user/get-font-variants-empty.json",
    );
    await this.mockRPC(
      "get-projects?team-id=*",
      "logged-in-user/get-projects-default.json",
    );
    await this.mockRPC(
      "get-team-members?team-id=*",
      "logged-in-user/get-team-members-your-penpot.json",
    );
    await this.mockRPC(
      "get-team-users?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await this.mockRPC(
      "get-unread-comment-threads?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await this.mockRPC(
      "get-team-recent-files?team-id=*",
      "logged-in-user/get-team-recent-files-empty.json",
    );
    await this.mockRPC(
      "get-profiles-for-file-comments",
      "logged-in-user/get-profiles-for-file-comments-empty.json",
    );
  }

  async setupLoginSuccess() {
    await this.mockRPC(
      "login-with-password",
      "logged-in-user/login-with-password-success.json",
    );
  }

  async setupLoginError() {
    await this.mockRPC(
      "login-with-password",
      "login-with-password-error.json",
      { status: 400 },
    );
  }
}

export default LoginPage;
