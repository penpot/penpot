import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class ViewerPage extends BaseWebSocketPage {
  static anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
  static anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

  /**
   * This should be called on `test.beforeEach`.
   *
   * @param {Page} page
   * @returns
   */
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);
  }

  async setupLoggedInUser() {
    await this.mockRPC(
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );
  }

  async setupEmptyFile() {
    await this.mockRPC(
      /get\-view\-only\-bundle\?/,
      "viewer/get-view-only-bundle-empty-file.json",
    );
    await this.mockRPC(
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await this.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "viewer/get-file-fragment-empty-file.json",
    );
  }

  async setupFileWithSingleBoard() {
    await this.mockRPC(
      /get\-view\-only\-bundle\?/,
      "viewer/get-view-only-bundle-single-board.json",
    );
    await this.mockRPC(
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await this.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "viewer/get-file-fragment-single-board.json",
    );
  }

  async setupFileWithMultipleBoards() {
    await this.mockRPC(
      /get\-view\-only\-bundle\?/,
      "viewer/get-view-only-bundle-multiple-boards.json",
    );
    await this.mockRPC(
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-empty.json",
    );
    await this.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "viewer/get-file-fragment-multiple-boards.json",
    );
  }

  async setupFileWithComments() {
    await this.mockRPC(
      /get\-view\-only\-bundle\?/,
      "viewer/get-view-only-bundle-single-board.json",
    );
    await this.mockRPC(
      "get-comment-threads?file-id=*",
      "workspace/get-comment-threads-not-empty.json",
    );
    await this.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "viewer/get-file-fragment-single-board.json",
    );
    await this.mockRPC(
      "get-comments?thread-id=*",
      "workspace/get-thread-comments.json",
    );
    await this.mockRPC(
      "update-comment-thread-status",
      "workspace/update-comment-thread-status.json",
    );
  }

  #ws = null;

  constructor(page) {
    super(page);
  }

  async goToViewer({
    fileId = ViewerPage.anyFileId,
    pageId = ViewerPage.anyPageId,
  } = {}) {
    await this.page.goto(
      `/#/view?file-id=${fileId}&page-id=${pageId}&section=interactions&index=0`,
    );

    this.#ws = await this.waitForNotificationsWebSocket();
    await this.#ws.mockOpen();
  }

  async cleanUp() {
    await this.#ws.mockClose();
  }

  async showComments(clickOptions = {}) {
    await this.page
      .getByRole("button", { name: "Comments (G C)" })
      .click(clickOptions);
  }

  async showCommentsThread(number, clickOptions = {}) {
    await this.page
      .getByTestId(`floating-thread-bubble-${number.toString()}`)
      .click(clickOptions);
  }

  async showCode(clickOptions = {}) {
    await this.page
      .getByRole("button", { name: "Inspect (G I)" })
      .click(clickOptions);
  }
}
