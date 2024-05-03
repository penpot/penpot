import { test, expect } from "@playwright/test";
import { interceptRPC, interceptRPCByRegex } from "./helpers/MockAPI";
import { WebSocketManager } from "./helpers/MockWebSocket";
import { presenceFixture } from "./fixtures/workspace/ws-notifications";

const anyProjectId = "c7ce0794-0992-8105-8004-38e630f7920b";
const anyFileId = "c7ce0794-0992-8105-8004-38f280443849";
const anyPageId = "c7ce0794-0992-8105-8004-38f28044384a";

const setupWorkspaceUser = (page) => {
  interceptRPC(page, "get-profile", "logged-in-user/get-profile-logged-in.json");
  interceptRPC(page, "get-team-users?file-id=*", "logged-in-user/get-team-users-single-user.json");
  interceptRPC(page, "get-comment-threads?file-id=*", "workspace/get-comment-threads-empty.json");
  interceptRPC(page, "get-project?id=*", "workspace/get-project-default.json");
  interceptRPC(page, "get-team?id=*", "workspace/get-team-default.json");
  interceptRPCByRegex(page, /get\-file\?/, "workspace/get-file-blank.json");
  interceptRPC(
    page,
    "get-file-object-thumbnails?file-id=*",
    "workspace/get-file-object-thumbnails-blank.json",
  );
  interceptRPC(
    page,
    "get-profiles-for-file-comments?file-id=*",
    "workspace/get-profile-for-file-comments.json",
  );
  interceptRPC(page, "get-font-variants?team-id=*", "workspace/get-font-variants-empty.json");
  interceptRPC(page, "get-file-fragment?file-id=*", "workspace/get-file-fragment-blank.json");
  interceptRPC(page, "get-file-libraries?file-id=*", "workspace/get-file-libraries-empty.json");
};

test.beforeEach(async ({ page }) => {
  await WebSocketManager.init(page);
});

test("User loads worskpace with empty file", async ({ page }) => {
  await setupWorkspaceUser(page);

  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  await expect(page.getByTestId("page-name")).toHaveText("Page 1");
});

test.only("User receives notifications updates in the workspace", async ({ page }) => {
  await setupWorkspaceUser(page);
  await page.goto(`/#/workspace/${anyProjectId}/${anyFileId}?page-id=${anyPageId}`);

  await page.evaluate(async () => {
    const ws = await WebSocket.waitForURL("ws://0.0.0.0:3500/ws/notifications");
    ws.mockOpen();
  });

  await expect(page.getByTestId("page-name")).toHaveText("Page 1");

  await page.evaluate(
    async ({ presenceFixture }) => {
      const ws = await WebSocket.waitForURL("ws://0.0.0.0:3500/ws/notifications");
      ws.mockMessage(JSON.stringify(presenceFixture));
    },
    { presenceFixture },
  );

  expect(page.getByTestId("active-users-list").getByAltText("Princesa Leia")).toHaveCount(2);

  await page.evaluate(async () => {
    const ws = await WebSocket.waitForURL("ws://0.0.0.0:3500/ws/notifications");
    ws.mockClose();
  });
});
