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

test("User receives presence notifications updates in the workspace", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();

  await workspacePage.goToWorkspace();
  await workspacePage.sendPresenceMessage(presenceFixture);

  await expect(
    page.getByTestId("active-users-list").getByAltText("Princesa Leia"),
  ).toHaveCount(2);
});

test("User draws a rect", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace();
  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);

  const shape = await workspacePage.rootShape.locator("rect");
  await expect(shape).toHaveAttribute("width", "200");
  await expect(shape).toHaveAttribute("height", "100");
});

test("User makes a group", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-not-empty.json",
  );
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
  });
  await workspacePage.clickLeafLayer("Rectangle");
  await workspacePage.page.keyboard.press("Control+g");
  await workspacePage.expectSelectedLayer("Group");
});

test("Bug 7654 - Toolbar keeps toggling on and off on spacebar press", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.toggleToolbarButton.click();
  await workspacePage.page.keyboard.press("Backspace");
  await workspacePage.page.keyboard.press("Enter");
  await workspacePage.expectHiddenToolbarOptions();
});

test("Bug 7525 - User moves a scrollbar and no selciont rectangle appears", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-not-empty.json",
  );
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
    pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
  });

  // Move created rect to a corner, in orther to get scrollbars
  await workspacePage.panOnViewportAt(128, 128, 300, 300);

  // Check scrollbars appear
  const horizontalScrollbar = workspacePage.horizontalScrollbar;
  await expect(horizontalScrollbar).toBeVisible();

  // Grab scrollbar and move
  const { x, y } = await horizontalScrollbar.boundingBox();
  await page.waitForTimeout(100);
  await workspacePage.viewport.hover({ position: { x: x, y: y + 5 } });
  await page.mouse.down();
  await workspacePage.viewport.hover({ position: { x: x - 130, y: y - 95 } });

  await expect(workspacePage.selectionRect).not.toBeInViewport();
});

test("User adds a library and its automatically selected in the color palette", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "link-file-to-library",
    "workspace/link-file-to-library.json",
  );
  await workspacePage.mockRPC(
    "unlink-file-from-library",
    "workspace/unlink-file-from-library.json",
  );
  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-libraries-non-empty.json",
  );

  await workspacePage.goToWorkspace();

  // Add Testing library 1
  await workspacePage.clickColorPalette();
  await workspacePage.clickAssets();
  // Now the get-file call should return a library
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
  await workspacePage.openLibrariesModal();
  await workspacePage.clickLibrary("Testing library 1");
  await workspacePage.closeLibrariesModal();

  await expect(
    workspacePage.palette.getByRole("button", { name: "test-color-187cd5" }),
  ).toBeVisible();

  // Remove Testing library 1
  await workspacePage.openLibrariesModal();
  await workspacePage.clickLibrary("Testing library 1");
  await workspacePage.closeLibrariesModal();

  await expect(
    workspacePage.palette.getByText(
      "There are no color styles in your library yet",
    ),
  ).toBeVisible();
});

test("Bug 7489 - Workspace-palette items stay hidden when opening with keyboard-shortcut", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();

  await workspacePage.clickTogglePalettesVisibility();
  await workspacePage.page.keyboard.press("Alt+t");

  await expect(
    workspacePage.palette.getByText(
      "There are no typography styles in your library yet",
    ),
  ).toBeVisible();
});

test("Bug 8784 - Use keyboard arrow to move inside a text input does not change tabs", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();
  await workspacePage.pageName.click();
  await page.keyboard.press("ArrowLeft");

  await expect(workspacePage.pageName).toHaveText("Page 1");
});

test("Bug 9066 - Problem with grid layout", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=e179d9df-de35-80bf-8005-2861e849b3f7",
    "workspace/get-file-fragment-9066-1.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=e179d9df-de35-80bf-8005-2861e849785e",
    "workspace/get-file-fragment-9066-2.json",
  );

  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
    pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
  });

  await workspacePage.clickToggableLayer("Board");
  await workspacePage.clickToggableLayer("Group");
  await page.getByText("A", { exact: true }).click();

  await workspacePage.rightSidebar.getByTestId("swap-component-btn").click();

  await page.getByTitle("C", { exact: true }).click();

  await expect(
    page.getByTestId("children-6ad3e6b9-c5a0-80cf-8005-283bbe378bcb"),
  ).toHaveText(["CBCDEF"]);
});

test("[Taiga #9929] Paste text in workspace", async ({ page, context }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.evaluate(() => navigator.clipboard.writeText("Lorem ipsum dolor"));
  await workspacePage.viewport.click({ button: "right" });
  await page.getByText("PasteCtrlV").click();
  await workspacePage.viewport
    .getByRole("textbox")
    .getByText("Lorem ipsum dolor");
});
