import { expect } from "@playwright/test";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class WorkspacePage extends BaseWebSocketPage {
  /**
   * This should be called on `test.beforeEach`.
   *
   * @param {Page} page
   * @returns
   */
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPC(page, "get-profile", "logged-in-user/get-profile-logged-in.json");
    await BaseWebSocketPage.mockRPC(
      page,
      "get-team-users?file-id=*",
      "logged-in-user/get-team-users-single-user.json",
    );
    await BaseWebSocketPage.mockRPC(
      page,
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await BaseWebSocketPage.mockRPC(page, "get-project?id=*", "workspace/get-project-default.json");
    await BaseWebSocketPage.mockRPC(page, "get-team?id=*", "workspace/get-team-default.json");
    await BaseWebSocketPage.mockRPC(
      page,
      "get-profiles-for-file-comments?file-id=*",
      "workspace/get-profile-for-file-comments.json",
    );
  }

  static anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
  static anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
  static anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

  #ws = null;

  constructor(page) {
    super(page);
    this.pageName = page.getByTestId("page-name");
    this.presentUserListItems = page.getByTestId("active-users-list").getByAltText("Princesa Leia");
    this.viewport = page.getByTestId("viewport");
    this.rootShape = page.locator(`[id="shape-00000000-0000-0000-0000-000000000000"]`);
    this.rectShapeButton = page.getByRole("button", { name: "Rectangle (R)" });
  }

  async goToWorkspace() {
    await this.page.goto(
      `/#/workspace/${WorkspacePage.anyProjectId}/${WorkspacePage.anyFileId}?page-id=${WorkspacePage.anyPageId}`,
    );

    this.#ws = await this.waitForNotificationsWebSocket();
    await this.#ws.mockOpen();
    await this.#waitForWebSocketReadiness();
  }

  async #waitForWebSocketReadiness() {
    // TODO: find a better event to settle whether the app is ready to receive notifications via ws
    await expect(this.pageName).toHaveText("Page 1");
  }

  async sendPresenceMessage(fixture) {
    await this.#ws.mockMessage(JSON.stringify(fixture));
  }

  async cleanUp() {
    await this.#ws.mockClose();
  }

  async setupEmptyFile() {
    await this.mockRPC("get-profile", "logged-in-user/get-profile-logged-in.json");
    await this.mockRPC("get-team-users?file-id=*", "logged-in-user/get-team-users-single-user.json");
    await this.mockRPC("get-comment-threads?file-id=*", "workspace/get-comment-threads-empty.json");
    await this.mockRPC("get-project?id=*", "workspace/get-project-default.json");
    await this.mockRPC("get-team?id=*", "workspace/get-team-default.json");
    await this.mockRPC(
      "get-profiles-for-file-comments?file-id=*",
      "workspace/get-profile-for-file-comments.json",
    );
    await this.mockRPC(/get\-file\?/, "workspace/get-file-blank.json");
    await this.mockRPC(
      "get-file-object-thumbnails?file-id=*",
      "workspace/get-file-object-thumbnails-blank.json",
    );
    await this.mockRPC("get-font-variants?team-id=*", "workspace/get-font-variants-empty.json");
    await this.mockRPC("get-file-fragment?file-id=*", "workspace/get-file-fragment-blank.json");
    await this.mockRPC("get-file-libraries?file-id=*", "workspace/get-file-libraries-empty.json");
  }

  async clickWithDragViewportAt(x, y, width, height) {
    await this.page.waitForTimeout(100);
    await this.viewport.hover({ position: { x, y } });
    await this.page.mouse.down();
    await this.viewport.hover({ position: { x: x + width, y: y + height } });
    await this.page.mouse.up();
  }
}
