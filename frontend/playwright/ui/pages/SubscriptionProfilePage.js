import { expect } from "@playwright/test";
import { DashboardPage } from "./DashboardPage";

export class SubscriptionProfilePage extends DashboardPage {
  static async init(page) {
    await DashboardPage.initWebSockets(page);

    await DashboardPage.mockRPC(
      page,
      "get-owned-teams",
      "subscription/get-owned-teams.json",
    );
  }

  constructor(page) {
    super(page);

    this.mainHeading = page.getByRole("heading", {
      name: "Subscription",
      level: 2,
    });
  }

  async goToSubscriptions() {
    await this.page.goto(`#/settings/subscriptions`);
    await expect(this.mainHeading).toBeVisible();
  }
}

export default SubscriptionProfilePage;
