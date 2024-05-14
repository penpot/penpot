import { MockWebSocketHelper } from "../../helpers/MockWebSocketHelper";
import BasePage from "./BasePage";

export class BaseWebSocketPage extends BasePage {
  /**
   * This should be called on `test.beforeEach`.
   *
   * @param {Page} page
   * @returns
   */
  static async initWebSockets(page) {
    await MockWebSocketHelper.init(page);
  }

  /**
   * Returns a promise that resolves when a WebSocket with the given URL is created.
   *
   * @param {string} url
   * @returns {Promise<MockWebSocketHelper>}
   */
  async waitForWebSocket(url) {
    return MockWebSocketHelper.waitForURL(url);
  }

  /**
   *
   * @returns {Promise<MockWebSocketHelper>}
   */
  async waitForNotificationsWebSocket() {
    return this.waitForWebSocket("ws://localhost:3000/ws/notifications");
  }
}
