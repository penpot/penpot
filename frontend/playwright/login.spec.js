import { test, expect } from "@playwright/test";
import { interceptRPC } from "./helpers";

const setupLoggedOutUser = async (page) => {
  await interceptRPC(page, "get-profile", "get-profile-anonymous.json");
  await interceptRPC(page, "login-with-password", "logged-in-user/login-with-password-success.json");
};

// TODO: maybe Playwright's fixtures are the right way to do this?
const setupDashboardUser = async (page) => {
  await interceptRPC(page, "get-profile", "logged-in-user/get-profile-logged-in.json");
  await interceptRPC(page, "get-teams", "logged-in-user/get-teams-default.json");
  await interceptRPC(page, "get-font-variants?team-id=*", "logged-in-user/get-font-variants-empty.json");
  await interceptRPC(page, "get-projects?team-id=*", "logged-in-user/get-projects-default.json");
  await interceptRPC(page, "get-team-members?team-id=*", "logged-in-user/get-team-members-your-penpot.json");
  await interceptRPC(page, "get-team-users?team-id=*", "logged-in-user/get-team-users-single-user.json");
  await interceptRPC(
    page,
    "get-unread-comment-threads?team-id=*",
    "logged-in-user/get-team-users-single-user.json",
  );
  await interceptRPC(
    page,
    "get-team-recent-files?team-id=*",
    "logged-in-user/get-team-recent-files-empty.json",
  );
  await interceptRPC(
    page,
    "get-profiles-for-file-comments",
    "logged-in-user/get-profiles-for-file-comments-empty.json",
  );
};

test("Shows login page when going to index and user is logged out", async ({ page }) => {
  setupLoggedOutUser(page);

  await page.goto("/");

  await expect(page).toHaveURL(/auth\/login$/);
  await expect(page.getByText("Log into my account")).toBeVisible();
});

test("User logs in by filling the login form", async ({ page }) => {
  setupLoggedOutUser(page);

  await page.goto("/#/auth/login");

  setupDashboardUser(page);

  await page.getByLabel("Email").fill("foo@example.com");
  await page.getByLabel("Password").fill("loremipsum");

  await page.getByRole("button", { name: "Login" }).click();

  await expect(page).toHaveURL(/dashboard/);
});
