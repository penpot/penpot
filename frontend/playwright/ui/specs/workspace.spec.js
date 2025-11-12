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

test("User opens a file with a bad page id", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace({
    pageId: "badpage",
  });

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

test("Bug 10179 - Drag & drop doesn't add colors to the Recent Colors palette", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.goToWorkspace();
  await workspacePage.moveButton.click();

  await workspacePage.page.keyboard.press("Alt+p");

  await expect(
    workspacePage.palette.getByText(
      "There are no color styles in your library yet",
    ),
  ).toBeVisible();

  await page.getByRole("button", { name: "#E8E9EA" }).click();
  await expect(page.getByTestId("colorpicker")).toBeVisible();
  const handler = await page.getByTestId("ramp-handler");
  await expect(handler).toBeVisible();
  const saturation_selection = await page.getByTestId(
    "value-saturation-selector",
  );
  await expect(saturation_selection).toBeVisible();
  const saturation_box = await saturation_selection.boundingBox();
  const handler_box = await handler.boundingBox();
  await page.mouse.move(
    handler_box.x + handler_box.width,
    handler_box.y + handler_box.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(
    saturation_box.x + saturation_box.width / 2,
    saturation_box.y + saturation_box.height / 2,
  );
  await page.mouse.up();
  await expect(
    workspacePage.palette.getByText(
      "There are no color styles in your library yet",
    ),
  ).not.toBeVisible();
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

  await workspacePage.rightSidebar.getByTestId("component-pill-button").click();

  await page.getByTitle("C", { exact: true }).click();

  await expect(
    page.getByTestId("children-6ad3e6b9-c5a0-80cf-8005-283bbe378bcb"),
  ).toHaveText(["CBCDEF"]);
});

test("User have toolbar", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  await expect(page.getByTitle("toggle toolbar")).toBeVisible();
  await expect(page.getByTitle("design")).toBeVisible();
});

test("User have edition menu entries", async ({ page }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  await page.getByRole("button", { name: "Main menu" }).click();
  await page.getByText("file").last().click();

  await expect(page.getByText("Add as Shared Library")).toBeVisible();

  await page.getByText("edit").click();

  await expect(page.getByText("Undo")).toBeVisible();
  await expect(page.getByText("Redo")).toBeVisible();
});

test("Copy/paste properties", async ({ page, context }) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(
    /get\-file\?/,
    "workspace/get-file-copy-paste.json",
  );
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-copy-paste-fragment.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "870f9f10-87b5-8137-8005-934804124660",
    pageId: "870f9f10-87b5-8137-8005-934804124661",
  });

  // Access to the read/write clipboard necesary for this functionality
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);

  await page.getByTestId("layer-item").getByRole("button").first().click();
  await page
    .getByTestId("children-0eef4dd0-b39b-807a-8005-934805578f93")
    .getByText("Rectangle")
    .click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Copy properties").click();

  await page
    .getByTestId("layer-item")
    .getByText("Uno dos tres cuatro")
    .click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Paste properties").click();

  await page.getByText("Rectangle").first().click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Paste properties").click();

  await page.getByText("Board").nth(2).click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Paste properties").click();

  await page
    .getByTestId("layer-item")
    .locator("div")
    .filter({ hasText: "Path" })
    .nth(1)
    .click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Paste properties").click();

  await page.getByText("Ellipse").click({ button: "right" });
  await page.getByText("Copy/Paste as").hover();
  await page.getByText("Paste properties").click();
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

test("[Taiga #9930] Zoom fit all doesn't fits all", async ({
  page,
  context,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-9930.json");
  await workspacePage.goToWorkspace({
    fileId: "8f843b59-7fbb-81ce-8005-aa6d47ae3111",
    pageId: "fb9798e7-a547-80ae-8005-9ffda4a13e2c",
  });

  const zoom = await page.getByTitle("Zoom");
  await zoom.click();

  const zoomIn = await page.getByRole("button", { name: "Zoom in" });
  await zoomIn.click();
  await zoomIn.click();
  await zoomIn.click();

  // Zoom fit all
  await page.keyboard.press("Shift+1");

  const ids = [
    "shape-165d1e5a-5873-8010-8005-9ffdbeaeec59",
    "shape-165d1e5a-5873-8010-8005-9ffdbeaf8d8a",
    "shape-165d1e5a-5873-8010-8005-9ffdbeaf8d9e",
    "shape-165d1e5a-5873-8010-8005-9ffdbeb053d9",
    "shape-165d1e5a-5873-8010-8005-9ffdbeb09738",
    "shape-165d1e5a-5873-8010-8005-9ffdbeb0f3fc",
  ];

  function contains(container, contained) {
    return (
      container.x <= contained.x &&
      container.y <= contained.y &&
      container.width >= contained.width &&
      container.height >= contained.height
    );
  }

  const viewportBoundingBox = await workspacePage.viewport.boundingBox();
  for (const id of ids) {
    const shape = await page.locator(`.ws-shape-wrapper > g#${id}`);
    const shapeBoundingBox = await shape.boundingBox();
    expect(contains(viewportBoundingBox, shapeBoundingBox)).toBeTruthy();
  }
});

test("Bug 9877, user navigation to dashboard from header goes to blank page", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);

  await workspacePage.goToWorkspace();

  const popupPromise = page.waitForEvent("popup");
  await page.getByText("Drafts").click();

  const popup = await popupPromise;
  await expect(popup).toHaveURL(
    /&project-id=c7ce0794-0992-8105-8004-38e630f7920b/,
  );
});

test("Bug 8371 - Flatten option is not visible in context menu", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockGetFile("workspace/get-file-8371.json");
  await workspacePage.goToWorkspace({
    fileId: "7ce7750c-3efe-8009-8006-bf390a415df2",
    pageId: "7ce7750c-3efe-8009-8006-bf390a415df3",
  });

  const shape = workspacePage.page.locator(
    `[id="shape-40c555bd-1810-809a-8006-bf3912728203"]`,
  );

  await workspacePage.clickLeafLayer("Union");
  await workspacePage.page
    .locator(".viewport-selrect")
    .click({ button: "right" });
  await expect(workspacePage.contextMenuForShape).toBeVisible();
  await expect(
    workspacePage.contextMenuForShape
      .getByText("Flatten")
      // there are hidden elements in the context menu (in submenus) with "Flatten" text
      .filter({ visible: true }),
  ).toBeVisible();
});
