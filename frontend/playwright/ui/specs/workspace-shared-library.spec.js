import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

const mainFileId = "3622460c-3408-81e2-8005-2fd0e55888b7";
const sharedFileId = "3622460c-3408-81e2-8005-2fc938010233";

const mainPageId = "3622460c-3408-81e2-8005-2fd0e55888b8";

const mainFileFragmentId1 = "6777aca0-5737-8169-8005-33b1ab0bcf8a";
const mainFileFragmentId2 = "6777aca0-5737-8169-8005-33b3eb8de897";

const sharedFileFragmentId1 = "3622460c-3408-81e2-8005-31859c15ff91";
const sharedFileFragmentId2 = "3622460c-3408-81e2-8005-31859c15ff90";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

// Fix for https://tree.taiga.io/project/penpot/issue/9042
test("Bug 9056 - 'More info' doesn't open the update tab", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.mockRPC(
    /get\-file\?id=3622460c-3408-81e2-8005-2fd0e55888b7/,
    "workspace/get-file-9056_main.json",
  );

  await workspacePage.mockRPC(
    /get\-file\?id=3622460c-3408-81e2-8005-2fc938010233/,
    "workspace/get-file-9056_shared.json",
  );

  await workspacePage.mockRPC(
    "get-file-libraries?file-id=*",
    "workspace/get-file-libraries-9056.json",
  );

  await workspacePage.mockRPC(
    `get-file-fragment?file-id=${mainFileId}&fragment-id=${mainFileFragmentId1}`,
    "workspace/get-file-fragment-9056_main-1.json",
  );

  await workspacePage.mockRPC(
    `get-file-fragment?file-id=${mainFileId}&fragment-id=${mainFileFragmentId2}`,
    "workspace/get-file-fragment-9056_main-2.json",
  );

  await workspacePage.mockRPC(
    `get-file-fragment?file-id=${sharedFileId}&fragment-id=${sharedFileFragmentId1}`,
    "workspace/get-file-fragment-9056_shared-1.json",
  );

  await workspacePage.mockRPC(
    `get-file-fragment?file-id=${sharedFileId}&fragment-id=${sharedFileFragmentId2}`,
    "workspace/get-file-fragment-9056_shared-2.json",
  );

  await workspacePage.goToWorkspace({
    fileId: mainFileId,
    pageId: mainPageId,
  });

  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-files-9056.json",
  );

  await page.getByRole("button", { name: "More info" }).click();

  await expect(page.getByRole("tabpanel", { name: "UPDATES" })).toHaveText(
    /library updates/i,
  );
});

test("Bug 10113 - Empty library modal for non-empty library", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page);

  await workspace.setupEmptyFile(page);
  await workspace.mockRPC(/get\-file\?/, "workspace/get-file-10113.json");
  await workspace.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-10113.json",
  );
  await workspace.mockRPC(/get\-file\?/, "workspace/get-file-10113.json");
  await workspace.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-files-empty.json",
  );
  await workspace.mockRPC(
    "set-file-shared",
    "workspace/set-file-shared-10113.json",
  );

  await workspace.goToWorkspace({
    fileId: "5b7ebd2b-2907-80db-8005-b9d67c20cf2e",
    pageId: "5b7ebd2b-2907-80db-8005-b9d67c20cf2f",
  });

  await workspace.clickAssets();
  await workspace.openLibrariesModal();

  await workspace.librariesModal
    .getByRole("button", { name: "Publish" })
    .click();

  await expect(
    workspace.page.getByText("Publish empty library"),
  ).not.toBeVisible();
});
