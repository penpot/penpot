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
    await this.mockRPC("get-profile", "logged-in-user/get-profile-logged-in.json");
  }

  async setupEmptyFile() {
    await this.mockRPC(/get\-view\-only\-bundle\?/, "viewer/get-view-only-bundle-empty-file.json");
    await this.mockRPC("get-comment-threads?file-id=*", "workspace/get-comment-threads-empty.json");
    await this.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "viewer/get-file-fragment-empty-file.json",
    );
  }

  #ws = null;

  constructor(page) {
    super(page);
  }

  async goToViewer({ fileId = ViewerPage.anyFileId, pageId = ViewerPage.anyPageId } = {}) {
    await this.page.goto(`/#/view/${fileId}?page-id=${pageId}&section=interactions&index=0`);

    this.#ws = await this.waitForNotificationsWebSocket();
    await this.#ws.mockOpen();
  }

  async cleanUp() {
    await this.#ws.mockClose();
  }
}
