import { test, expect } from "@playwright/test";
import { BasePage } from "../pages/BasePage";
import { MockWebSocketHelper } from "../../helpers/MockWebSocketHelper";
import { presenceFixture } from "../../data/workspace/ws-notifications";

const anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
const anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
const anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

const setupWorkspaceUser = (page) => {
  BasePage.mockRPC(page, "get-profile", "logged-in-user/get-profile-logged-in.json");
  BasePage.mockRPC(page, "get-team-users?file-id=*", "logged-in-user/get-team-users-single-user.json");
  BasePage.mockRPC(page, "get-comment-threads?file-id=*", "workspace/get-comment-threads-empty.json");
  BasePage.mockRPC(page, "get-project?id=*", "workspace/get-project-default.json");
  BasePage.mockRPC(page, "get-team?id=*", "workspace/get-team-default.json");
  BasePage.mockRPC(page, /get\-file\?/, "workspace/get-file-blank.json");
  BasePage.mockRPC(
    page,
    "get-file-object-thumbnails?file-id=*",
    "workspace/get-file-object-thumbnails-blank.json",
  );
  BasePage.mockRPC(
    page,
    "get-profiles-for-file-comments?file-id=*",
    "workspace/get-profile-for-file-comments.json",
  );
  BasePage.mockRPC(page, "get-font-variants?team-id=*", "workspace/get-font-variants-empty.json");
  BasePage.mockRPC(page, "get-file-fragment?file-id=*", "workspace/get-file-fragment-blank.json");
  BasePage.mockRPC(page, "get-file-libraries?file-id=*", "workspace/get-file-libraries-empty.json");
};

test.beforeEach(async ({ page }) => {
  await MockWebSocketHelper.init(page);
});

test("User loads worskpace with empty file", async ({ page }) => {
  await setupWorkspaceUser(page);

  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  await expect(page.getByTestId("page-name")).toHaveText("Page 1");
});

test("User receives notifications updates in the workspace", async ({ page }) => {
  await setupWorkspaceUser(page);
  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  const ws = await MockWebSocketHelper.waitForURL("ws://0.0.0.0:3500/ws/notifications")
  await ws.mockOpen();
  await expect(page.getByTestId("page-name")).toHaveText("Page 1");
  await ws.mockMessage(JSON.stringify(presenceFixture));
  await expect(page.getByTestId("active-users-list").getByAltText("Princesa Leia")).toHaveCount(2);
  await ws.mockClose();
});
