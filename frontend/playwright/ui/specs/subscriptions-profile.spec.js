import { test, expect } from "@playwright/test";
import SubscriptionProfilePage from "../pages/SubscriptionProfilePage";

test.beforeEach(async ({ page }) => {
  await SubscriptionProfilePage.init(page);

  await SubscriptionProfilePage.mockConfigFlags(page, [
    "enable-subscriptions",
    "disable-onboarding",
  ]);
});

test.describe("Subscriptions: profile", () => {
  test("When subscription is professional there is no manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "logged-in-user/get-profile-logged-in.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).not.toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$7")).toBeVisible();

    await expect(page.getByText("$950")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).toBeVisible();
  });

  test("When subscription is unlimited there is manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-unlimited-subscription.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$0")).toBeVisible();

    await expect(page.getByText("$950")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).not.toBeVisible();

    await expect(
      page.getByRole("button", { name: "Subscribe" }).first(),
    ).toBeVisible();
  });

  test("When subscription is enteprise there is manage subscription link", async ({
    page,
  }) => {
    await SubscriptionProfilePage.mockRPC(
      page,
      "get-profile",
      "subscription/get-profile-enterprise-subscription.json",
    );

    const subscriptionProfilePage = new SubscriptionProfilePage(page);

    await subscriptionProfilePage.goToSubscriptions();

    await expect(
      page.getByRole("button", { name: "Manage your subscription" }),
    ).toBeVisible();

    await expect(
      page.getByRole("heading", { name: "Other Penpot plans", level: 3 }),
    ).toBeVisible();

    await expect(page.getByText("$0")).toBeVisible();

    await expect(page.getByText("$7")).toBeVisible();

    await expect(
      page.getByRole("button", { name: "Try it free for 14 days" }).first(),
    ).not.toBeVisible();

    await expect(
      page.getByRole("button", { name: "Subscribe" }).first(),
    ).toBeVisible();
  });
});
