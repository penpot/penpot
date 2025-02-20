import { expect } from "@playwright/test";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class DashboardPage extends BaseWebSocketPage {
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPC(
      page,
      "get-teams",
      "logged-in-user/get-teams-default.json",
    );
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
  static secondTeamId = "dd33ff88-f4e5-8033-8003-8096cc07bdf3";
  static newTeamId = "0b5bcbca-32ab-81eb-8005-a153d23d7739";
  static draftProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";

  constructor(page) {
    super(page);

    this.sidebar = page.getByTestId("dashboard-sidebar");
    this.sidebarMenu = this.sidebar.getByRole("menu");
    this.mainHeading = page
      .getByTestId("dashboard-header")
      .getByRole("heading", { level: 1 });

    this.addProjectButton = page.getByRole("button", { name: "+ NEW PROJECT" });
    this.projectName = page.getByText("Project 1");

    this.draftsLink = this.sidebar.getByText("Drafts");
    this.fontsLink = this.sidebar.getByText("Fonts");
    this.librariesLink = this.sidebar.getByText("Libraries");

    this.searchButton = page.getByRole("button", { name: "dashboard-search" });
    this.searchInput = page.getByPlaceholder("Search…");

    this.teamDropdown = this.sidebar.getByRole("button", {
      name: "Your Penpot",
    });
    this.userAccount = this.sidebar.getByRole("button", {
      name: /Princesa Leia/,
    });
    this.userProfileOption = this.sidebarMenu.getByText("Your account");
  }

  async setupDraftsEmpty() {
    await this.mockRPC(
      "get-project-files?project-id=*",
      "dashboard/get-project-files-empty.json",
    );
  }

  async setupSearchEmpty() {
    await this.mockRPC("search-files", "dashboard/search-files-empty.json", {
      method: "POST",
    });
  }

  async setupLibrariesEmpty() {
    await this.mockRPC(
      "get-team-shared-files?team-id=*",
      "dashboard/get-shared-files-empty.json",
    );
  }

  async setupDrafts() {
    await this.mockRPC(
      "get-project-files?project-id=*",
      "dashboard/get-project-files.json",
    );

    await this.mockRPC(/assets\/by-id/gi, "dashboard/thumbnail.png", {
      contentType: "image/png",
    });
  }

  async setupNewProject() {
    await this.mockRPC("create-project", "dashboard/create-project.json", {
      method: "POST",
    });
    await this.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-new.json",
    );
  }

  async setupDashboardFull() {
    await this.mockRPC(
      "get-projects?team-id=*",
      "dashboard/get-projects-full.json",
    );
    await this.mockRPC(
      "get-project-files?project-id=*",
      "dashboard/get-project-files.json",
    );
    await this.mockRPC(
      "get-team-shared-files?team-id=*",
      "dashboard/get-shared-files.json",
    );
    await this.mockRPC(
      "get-team-shared-files?project-id=*",
      "dashboard/get-shared-files.json",
    );
    await this.mockRPC(
      "get-team-recent-files?team-id=*",
      "dashboard/get-team-recent-files.json",
    );
    await this.mockRPC(
      "get-font-variants?team-id=*",
      "dashboard/get-font-variants.json",
    );
    await this.mockRPC("search-files", "dashboard/search-files.json", {
      method: "POST",
    });
    await this.mockRPC("delete-team", "dashboard/delete-team.json", {
      method: "POST",
    });
    await this.mockRPC("search-files", "dashboard/search-files.json");
    await this.mockRPC("get-teams", "logged-in-user/get-teams-complete.json");
  }

  async setupAccessTokensEmpty() {
    await this.mockRPC(
      "get-access-tokens",
      "dashboard/get-access-tokens-empty.json",
    );
  }

  async createAccessToken() {
    await this.mockRPC(
      "create-access-token",
      "dashboard/create-access-token.json",
      { method: "POST" },
    );
  }

  async setupAccessTokens() {
    await this.mockRPC("get-access-tokens", "dashboard/get-access-tokens.json");
  }

  async setupTeamInvitationsEmpty() {
    await this.mockRPC(
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations-empty.json",
    );
  }

  async setupTeamInvitations() {
    await this.mockRPC(
      "get-team-invitations?team-id=*",
      "dashboard/get-team-invitations.json",
    );
  }

  async setupTeamWebhooksEmpty() {
    await this.mockRPC(
      "get-webhooks?team-id=*",
      "dashboard/get-webhooks-empty.json",
    );
  }

  async setupTeamWebhooks() {
    await this.mockRPC("get-webhooks?team-id=*", "dashboard/get-webhooks.json");
  }

  async setupTeamSettings() {
    await this.mockRPC(
      "get-team-stats?team-id=*",
      "dashboard/get-team-stats.json",
    );
  }

  async goToDashboard() {
    await this.page.goto(
      `#/dashboard/recent?team-id=${DashboardPage.anyTeamId}`,
    );
    await expect(this.mainHeading).toBeVisible();
  }

  async goToSecondTeamDashboard() {
    await this.page.goto(
      `#/dashboard/recent?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSecondTeamMembersSection() {
    await this.page.goto(
      `#/dashboard/members?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSecondTeamInvitationsSection() {
    await this.page.goto(
      `#/dashboard/invitations?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSecondTeamWebhooksSection() {
    await this.page.goto(
      `#/dashboard/webhooks?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSecondTeamWebhooksSection() {
    await this.page.goto(
      `#/dashboard/webhooks?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSecondTeamSettingsSection() {
    await this.page.goto(
      `#/dashboard/settings?team-id=${DashboardPage.secondTeamId}`,
    );
  }

  async goToSearch() {
    await this.page.goto(
      `#/dashboard/search?team-id=${DashboardPage.anyTeamId}`,
    );
  }

  async goToDrafts() {
    await this.page.goto(
      `#/dashboard/files?team-id=${DashboardPage.anyTeamId}&project-id=${DashboardPage.draftProjectId}`,
    );
    await expect(this.mainHeading).toHaveText("Drafts");
  }

  async goToFonts() {
    await this.page.goto(
      `#/dashboard/fonts?team-id=${DashboardPage.anyTeamId}`,
    );
    await expect(this.mainHeading).toHaveText("Fonts");
  }

  async goToAccount() {
    await this.userAccount.click();

    await this.userProfileOption.click();
  }

  async goToLibraries() {
    await this.page.goto(
      `#/dashboard/libraries?team-id=${DashboardPage.anyTeamId}`,
    );
    await expect(this.mainHeading).toHaveText("Libraries");
  }
}

export default DashboardPage;
