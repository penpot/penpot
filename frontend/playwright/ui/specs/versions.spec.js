import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
});

test("Save and restore version", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);

  await workspacePage.mockRPC(/get\-file\?/, "workspace/versions-init.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=406b7b01-d3e2-80e4-8005-3138b7cc5f0b",
    "workspace/versions-init-fragment.json",
  );

  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.mockRPC(
    "push-audit-events",
    "workspace/audit-event-empty.json",
  );

  await workspacePage.mockRPC(
    "update-profile-props",
    "workspace/update-profile-empty.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "406b7b01-d3e2-80e4-8005-3138ac5d449c",
    pageId: "406b7b01-d3e2-80e4-8005-3138ac5d449d",
  });

  await workspacePage.moveButton.click();

  await workspacePage.mockRPC(
    "get-file-snapshots?file-id=*",
    "workspace/versions-snapshot-1.json",
  );

  await page.getByLabel("History").click();

  const saveVersionButton = page.getByRole("button", { name: "Save version" });
  await saveVersionButton.waitFor();

  await workspacePage.mockRPC(
    "create-file-snapshot",
    "workspace/versions-take-snapshot-1.json",
  );

  await workspacePage.mockRPC(
    "get-file-snapshots?file-id=*",
    "workspace/versions-snapshot-2.json",
  );

  await workspacePage.mockRPC(
    "update-file-snapshot",
    "workspace/versions-update-snapshot-1.json",
  );

  await saveVersionButton.click();

  await workspacePage.mockRPC(
    "get-file-snapshots?file-id=*",
    "workspace/versions-snapshot-3.json",
  );

  const textbox = page.getByRole("textbox");
  await textbox.waitFor();

  await page.getByRole("textbox").fill("INIT");
  await page.getByRole("textbox").press("Enter");

  await page
    .getByLabel("History", { exact: true })
    .locator("div")
    .nth(3)
    .hover();

  await workspacePage.mockRPC(
    "restore-file-snapshot",
    "workspace/versions-restore-snapshot-1.json",
  );

  await page.getByRole("button", { name: "Open version menu" }).click();
  await page.getByRole("button", { name: "Restore" }).click();
  await page.getByRole("button", { name: "Restore" }).click();

  // check that the history panel is closed after restore
  await expect(page.getByRole("tab", { name: "design" })).toBeVisible();
});

test("BUG 11006 - Fix history panel shortcut", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.mockRPC(/get\-file\?/, "workspace/versions-init.json");
  await workspacePage.mockRPC(
    "get-file-snapshots?file-id=*",
    "workspace/versions-snapshot-1.json",
  );

  await workspacePage.goToWorkspace();

  await page.keyboard.press("ControlOrMeta+Alt+h");

  await expect(
    workspacePage.rightSidebar.getByText("There are no versions yet"),
  ).toBeVisible();
});

test("BUG 13385 - Fix viewport not updating when restoring version", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("workspace/get-file-13385.json");
  await workspacePage.mockRPC("get-profiles-for-file-comments?file-id=*", "workspace/get-profiles-for-file-comments-13385.json");

  // navigate to workspace and check that the circle shape is not there
  await workspacePage.goToWorkspace();
  await expect(workspacePage.layers.getByText("Ellipse")).not.toBeVisible();

  // mock network requests to restore the version
  await workspacePage.mockGetFile("workspace/get-file-13385-2.json");
  await workspacePage.mockRPC("get-file-snapshots?file-id=*", "workspace/get-file-snapshots-13385.json");
  await workspacePage.mockRPC("restore-file-snapshot", "", {
    status: 204,
  });

  // request to restore the version
  await workspacePage.rightSidebar.getByRole("button", { name: "History" }).click();
  await workspacePage.rightSidebar.getByRole("button", { name: "Open version menu" }).click();
  await workspacePage.rightSidebar.getByRole("button", { name: "Restore" }).click();
  // confirm modal
  await workspacePage.page.getByRole("button", { name: /Restore/i }).click();

  // assert that the circle shape exists
  await expect(workspacePage.layers.getByText("Ellipse")).toBeVisible();
});