import { test, expect } from "@playwright/test";

test("Has title", async ({ page }) => {
  await page.route("**/api/main/methods/get-profile", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: "playwright/data/get-profile-anonymous.json",
    });
  });
  await page.goto("/");

  await expect(page).toHaveTitle(/Penpot/);
});
