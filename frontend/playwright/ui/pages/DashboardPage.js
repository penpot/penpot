import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class DashboardPage extends BaseWebSocketPage {
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in-no-onboarding.json",
    );
    await BaseWebSocketPage.mockRPC(page, "get-teams", "logged-in-user/get-teams-default.json");
    await BaseWebSocketPage.mockRPC(
      page,
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );

    await BaseWebSocketPage.mockRPC(
      page,
      "get-projects?team-id=*",
      "logged-in-user/get-projects-default.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-members?team-id=*",
      "logged-in-user/get-team-members-your-penpot.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-users?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-unread-comment-threads?team-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-recent-files?team-id=*",
      "logged-in-user/get-team-recent-files-empty.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-profiles-for-file-comments",
      "workspace/get-profile-for-file-comments.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-builtin-templates",
      "logged-in-user/get-built-in-templates-empty.json",
    );
  }

  static anyTeamId = "c7ce0794-0992-8105-8004-38e630f40f6d";

  static draftProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";

  constructor(page) {
    super(page);
    this.titleLabel = page.getByRole("heading", { name: "Projects" });
    this.addProjectBtn = page.getByRole("button", { name: "+ NEW PROJECT" });
    this.projectName = page.getByText("Project 1");
    this.draftTitle = page.getByRole("heading", { name: "Drafts" });
    this.draftLink = page.getByTestId("drafts-link-sidebar");
    this.draftsFile = page.getByText(/New File 1/);
  }

  async setupDraftsEmpty() {
    await this.mockRPC("get-project-files?project-id=*", "dashboard/get-project-files-empty.json");
  }

  async setupDrafts() {
    await this.mockRPC("get-project-files?project-id=*", "dashboard/get-project-files.json");
  }

  async setupNewProject() {
    await this.mockRPC("create-project", "dashboard/create-project.json", { method: "POST" });
    await this.mockRPC("get-projects?team-id=*", "dashboard/get-projects-new.json");
  }
  async goToWorkspace() {
    await this.page.goto(`#/dashboard/team/${DashboardPage.anyTeamId}/projects`);
  }

  async goToDrafts() {
    await this.page.goto(
      `#/dashboard/team/${DashboardPage.anyTeamId}/projects/${DashboardPage.draftProjectId}`,
    );
  }
}

export default DashboardPage;
