import { expect } from "@playwright/test";
import { BaseWebSocketPage } from "./BaseWebSocketPage";

export class SubscriptionProfilePage extends BaseWebSocketPage {
  static async init(page) {
    await BaseWebSocketPage.initWebSockets(page);

    await BaseWebSocketPage.mockRPC(
      page,
      "get-owned-teams",
      "subscription/get-owned-teams.json",
    );

  }

  constructor(page) {
    super(page);

    this.mainHeading = page.getByRole('heading', { name: 'Subscription', level: 2 });
  }

  async goToSubscriptions() {
    await this.page.goto(
      `#/settings/subscriptions`,
    );
    await expect(this.mainHeading).toBeVisible();
  }

}

export default SubscriptionProfilePage;
