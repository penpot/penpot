import { MockWebSocketHelper } from "../../helpers/MockWebSocketHelper";
import BasePage from "./BasePage";

export class BaseWebSocketPage extends BasePage {
  static async init(page) {
    await super.init(page);
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
