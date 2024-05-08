import { test, expect } from "@playwright/test";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";
import { MockWebSocketHelper } from "../../helpers/MockWebSocketHelper";
import { presenceFixture } from "../../data/workspace/ws-notifications";

const anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
const anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
const anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

const setupWorkspaceUser = (page) => {
  page.mockRPC("get-profile", "logged-in-user/get-profile-logged-in.json");
  page.mockRPC("get-team-users?file-id=*", "logged-in-user/get-team-users-single-user.json");
  page.mockRPC("get-comment-threads?file-id=*", "workspace/get-comment-threads-empty.json");
  page.mockRPC("get-project?id=*", "workspace/get-project-default.json");
  page.mockRPC("get-team?id=*", "workspace/get-team-default.json");
  page.mockRPC(/get\-file\?/, "workspace/get-file-blank.json");
  page.mockRPC("get-file-object-thumbnails?file-id=*", "workspace/get-file-object-thumbnails-blank.json");
  page.mockRPC("get-profiles-for-file-comments?file-id=*", "workspace/get-profile-for-file-comments.json");
  page.mockRPC("get-font-variants?team-id=*", "workspace/get-font-variants-empty.json");
  page.mockRPC("get-file-fragment?file-id=*", "workspace/get-file-fragment-blank.json");
  page.mockRPC("get-file-libraries?file-id=*", "workspace/get-file-libraries-empty.json");
};

test.beforeEach(async ({ page }) => {
  await MockWebSocketHelper.init(page);
});

test.skip("User loads worskpace with empty file", async ({ page }) => {
  await setupWorkspaceUser(page);

  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  await expect(page.getByTestId("page-name")).toHaveText("Page 1");
});

test.skip("User receives notifications updates in the workspace", async ({ page }) => {
  await setupWorkspaceUser(page);
  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  const ws = await MockWebSocketHelper.waitForURL("ws://0.0.0.0:3500/ws/notifications");
  await ws.mockOpen();
  await expect(page.getByTestId("page-name")).toHaveText("Page 1");
  await ws.mockMessage(JSON.stringify(presenceFixture));
  await expect(page.getByTestId("active-users-list").getByAltText("Princesa Leia")).toHaveCount(2);
  await ws.mockClose();
});
