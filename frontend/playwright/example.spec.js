import { test, expect } from "@playwright/test";

test("has title", async ({ page }) => {
  await page.route("**/api/rpc/command/get-profile", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: "playwright/fixtures/get-profile-anonymous.json",
    });
  });
  await page.goto("/");

  await expect(page).toHaveTitle(/Penpot/);
});
