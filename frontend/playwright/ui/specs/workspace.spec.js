import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { presenceFixture } from "../../data/workspace/ws-notifications";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test("User loads worskpace with empty file", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();

  await expect(workspacePage.pageName).toHaveText("Page 1");
});

test("User receives presence notifications updates in the workspace", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();

  await workspacePage.goToWorkspace();
  await workspacePage.sendPresenceMessage(presenceFixture);

  await expect(page.getByTestId("active-users-list").getByAltText("Princesa Leia")).toHaveCount(2);
});

test("User draws a rect", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-create-rect.json");

  await workspacePage.goToWorkspace();
  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);

  const shape = await workspacePage.rootShape.locator("rect");
  await expect(shape).toHaveAttribute("width", "200");
  await expect(shape).toHaveAttribute("height", "100");
});

test("User adds a library and its automatically selected in the color palette", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC("link-file-to-library", "workspace/link-file-to-library.json");
  await workspacePage.mockRPC("unlink-file-from-library", "workspace/unlink-file-from-library.json");
  await workspacePage.mockRPC("get-team-shared-files?team-id=*", "workspace/get-team-shared-libraries-non-empty.json");
  
  await workspacePage.goToWorkspace();

  // Add Testing library 1
  await workspacePage.clickColorPalette();
  await workspacePage.clickAssets();
  // Now the get-file call should return a library
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
  await workspacePage.clickLibraries();
  await workspacePage.clickLibrary("Testing library 1")
  await workspacePage.clickCloseLibraries(); 

  await expect(workspacePage.palette.getByRole("button", { name: "test-color-187cd5" })).toBeVisible();

  // Remove Testing library 1
  await workspacePage.clickLibraries();
  await workspacePage.clickLibrary("Testing library 1")
  await workspacePage.clickCloseLibraries();

  await expect(workspacePage.palette.getByText('There are no color styles in your library yet')).toBeVisible();
});

test("User makes a group", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");
  await workspacePage.mockRPC("update-file?id=*", "workspace/update-file-create-rect.json");

  await workspacePage.goToWorkspace({ 
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374", 
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375"
  });
  await workspacePage.clickLeafLayer("Rectangle");
  await workspacePage.page.keyboard.press("ControlOrMeta+g");
  await workspacePage.expectSelectedLayer("Group");
});
