export class WebSocketManager {
  static async init(page) {
    await page.addInitScript({ path: "playwright/scripts/MockWebSocket.js" });
  }
}
