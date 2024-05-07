import { interceptRPC } from "../../helpers/index";

class LoginPage {
  constructor(page) {
    this.page = page;
    this.loginButton = page.getByRole("button", { name: "Login" });
    this.password = page.getByLabel("Password");
    this.userName = page.getByLabel("Email");
    this.message = page.getByText("Email or password is incorrect");
    this.badLoginMsg = page.getByText("Enter a valid email please");
    this.initialHeading = page.getByRole("heading", { name: "Log into my account" });
  }

  url() {
    return this.page.url();
  }

  context() {
    return this.page.context();
  }

  async fillEmailAndPasswordInputs(email, password) {
    await this.userName.fill(email);
    await this.password.fill(password);
  }

  async clickLoginButton() {
    await this.loginButton.click();
  }

  async setupAllowedUser() {
    await interceptRPC(this.page, "get-profile", "logged-in-user/get-profile-logged-in.json");
    await interceptRPC(this.page, "get-teams", "logged-in-user/get-teams-default.json");
    await interceptRPC(
      this.page,
      "get-font-variants?team-id=*",
      "logged-in-user/get-font-variants-empty.json",
    );
    await interceptRPC(this.page, "get-projects?team-id=*", "logged-in-user/get-projects-default.json");
    await interceptRPC(
      this.page,
      "get-team-members?team-id=*",
      "logged-in-user/get-team-members-your-penpot.json",
    );
    await interceptRPC(
      this.page,
      "get-team-users?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await interceptRPC(
      this.page,
      "get-unread-comment-threads?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await interceptRPC(
      this.page,
      "get-team-recent-files?team-id=*",
      "logged-in-user/get-team-recent-files-empty.json",
    );
    await interceptRPC(
      this.page,
      "get-profiles-for-file-comments",
      "logged-in-user/get-profiles-for-file-comments-empty.json",
    );
  }

  async setupLoginSuccess() {
    await interceptRPC(this.page, "login-with-password", "logged-in-user/login-with-password-success.json");
  }

  async setupLoginError() {
    await interceptRPC(this.page, "login-with-password", "login-with-password-error.json", { status: 400 });
  }
}

export default LoginPage;
