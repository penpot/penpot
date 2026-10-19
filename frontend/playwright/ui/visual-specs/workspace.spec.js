import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

const setupFileWithAssets = async (workspace) => {
  const fileId = "015fda4f-caa6-8103-8004-862a00dd4f31";
  const pageId = "015fda4f-caa6-8103-8004-862a00ddbe94";
  const fragments = {
    "015fda4f-caa6-8103-8004-862a9e4b4d4b":
      "assets/get-file-fragment-with-assets-components.json",
    "015fda4f-caa6-8103-8004-862a9e4ad279":
      "assets/get-file-fragmnet-with-assets-page.json",
  };

  await workspace.setupEmptyFile();
  await workspace.mockRPC(/get\-file\?/, "assets/get-file-with-assets.json");

  for (const [id, fixture] of Object.entries(fragments)) {
    await workspace.mockRPC(
      `get-file-fragment?file-id=*&fragment-id=${id}`,
      fixture,
    );
  }

  return { fileId, pageId };
};

test.describe("Viewport", () => {
  test("Shows the workspace correctly for a blank file", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();

    await workspace.goToWorkspace();

    await expect(workspace.page).toHaveScreenshot();
  });

  test("User creates a rectangle and locks it", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile(page);
    await workspace.goToWorkspace();

    await workspace.rectShapeButton.click();
    await workspace.clickWithDragViewportAt(128, 128, 200, 100);
    await workspace.clickLeafLayer("Rectangle");

    await page.keyboard.press("Shift+Control+L");

    await expect(workspace.page).toHaveScreenshot();
  });
});

test.describe("Grid layout", () => {
  test("Shows the grid layout options and the grid editor", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9042.json");
    await workspace.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "workspace/get-file-fragment-9042.json",
    );
    await workspace.goToWorkspace({
      fileId: "af2494d0-39ba-8184-8005-230696f6df5c",
      pageId: "af2494d0-39ba-8184-8005-230696f6df5d",
    });

    await workspace.clickLeafLayer("Board");
    const section = page.getByTestId("inspect-layout");
    await expect(section.getByRole("button", { name: "Edit grid" }))
      .toBeVisible();
    // The buttons are a small part of the section: compare every pixel.
    await expect(section).toHaveScreenshot("grid-layout-options.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await section.getByRole("button", { name: "Edit grid" }).click();
    await expect(workspace.rightSidebar.getByText("GRID LAYOUT")).toBeVisible();
    // Expand the column tracks so their rows show too.
    await workspace.rightSidebar.getByText(/^Columns? \d/).click();
    await page.mouse.move(0, 0);
    await expect(workspace.rightSidebar).toHaveScreenshot(
      "grid-layout-editor.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );
  });
});

test.describe("Design tab", () => {
  test("Shows the boolean options when selecting two shapes", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();

    await workspace.rectShapeButton.click();
    await workspace.clickWithDragViewportAt(128, 128, 200, 100);
    await workspace.clickLeafLayer("Rectangle");
    await page.keyboard.press("Control+D");
    await expect(workspace.layers.getByText("Rectangle")).toHaveCount(2);
    await page.keyboard.press("Control+A");

    await expect(
      workspace.rightSidebar.getByRole("button", { name: "Flatten" }),
    ).toBeVisible();
    // The boolean options are a small part of the sidebar: compare every
    // pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });

  test("Shows the design tab when selecting a shape", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");

    await workspace.goToWorkspace({
      fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
      pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
    });

    await workspace.clickLeafLayer("Rectangle");

    await expect(workspace.page).toHaveScreenshot();
  });

  test("Shows expanded sections of the design tab", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-not-empty.json");

    await workspace.goToWorkspace({
      fileId: "6191cd35-bb1f-81f7-8004-7cc63d087374",
      pageId: "6191cd35-bb1f-81f7-8004-7cc63d087375",
    });

    await workspace.clickLeafLayer("Rectangle");
    await workspace.rightSidebar.getByTestId("add-stroke").click();

    await expect(workspace.page).toHaveScreenshot();
  });

  test("Shows the wrap and padding modes of a flex layout", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-7760.json");
    await workspace.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "workspace/get-file-fragment-7760.json",
    );
    await workspace.mockRPC(
      "update-file?id=*",
      "workspace/update-file-create-rect.json",
    );
    await workspace.goToWorkspace({
      fileId: "cd90e028-326a-80b4-8004-7cdec16ffad5",
      pageId: "cd90e028-326a-80b4-8004-7cdec16ffad6",
    });

    await workspace.clickLeafLayer("Flex Board");

    const section = workspace.rightSidebar.getByRole("region", {
      name: "Layout container section",
    });
    const wrap = section.getByRole("button", { name: /^(No w|W)rap$/ });
    const padding = section.getByRole("button", {
      name: "Show 4 sided padding options",
    });
    await expect(wrap).toBeVisible();
    // The buttons are a small part of the section: compare every pixel.
    await expect(section).toHaveScreenshot("flex-layout-rest.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await wrap.click();
    await padding.click();
    await padding.blur();
    await page.mouse.move(0, 0);
    await expect(section.getByRole("textbox", { name: /top padding/i }))
      .toBeVisible();
    await expect(section).toHaveScreenshot("flex-layout-pressed.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });

  test("Shows the align options when selecting two shapes", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();

    await workspace.rectShapeButton.click();
    await workspace.clickWithDragViewportAt(128, 128, 200, 100);
    await workspace.clickLeafLayer("Rectangle");
    await page.keyboard.press("Control+D");
    await expect(workspace.layers.getByText("Rectangle")).toHaveCount(2);
    await page.keyboard.press("Control+A");

    await expect(
      workspace.rightSidebar.getByRole("button", { name: /^Align left/ }),
    ).toBeVisible();
    // The align options are a small part of the sidebar: compare every pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });

  test("Shows the margin modes of a flex layout element", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-7760.json");
    await workspace.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "workspace/get-file-fragment-7760.json",
    );
    await workspace.mockRPC(
      "update-file?id=*",
      "workspace/update-file-create-rect.json",
    );
    await workspace.goToWorkspace({
      fileId: "cd90e028-326a-80b4-8004-7cdec16ffad5",
      pageId: "cd90e028-326a-80b4-8004-7cdec16ffad6",
    });

    await workspace.clickToggableLayer("Flex Board");
    await workspace.clickLeafLayer("Rectangle");

    const section = workspace.rightSidebar.getByRole("region", {
      name: "Layout item section",
    });
    const marginMode = section.getByRole("button", {
      name: "Margin - multiple",
    });
    await expect(marginMode).toBeVisible();
    // The margin button is a small part of the section: compare every pixel.
    await expect(section).toHaveScreenshot("margin-simple.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await marginMode.click();
    await marginMode.blur();
    await page.mouse.move(0, 0);
    await expect(section.getByRole("textbox", { name: "Top margin" }))
      .toBeVisible();
    await expect(section).toHaveScreenshot("margin-multiple.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Assets tab", () => {
  test("Shows the libraries modal correctly", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      "link-file-to-library",
      "workspace/link-file-to-library.json",
    );
    await workspace.mockRPC(
      "get-team-shared-files?team-id=*",
      "workspace/get-team-shared-libraries-non-empty.json",
    );

    await workspace.mockRPC(
      "push-audit-events",
      "workspace/audit-event-empty.json",
    );

    await workspace.goToWorkspace();
    await workspace.clickAssets();
    await workspace.openLibrariesModal();
    await expect(workspace.page).toHaveScreenshot();

    await workspace.librariesModal
      .getByRole("tab", { name: "Libraries" })
      .click();
    await expect(workspace.page).toHaveScreenshot();

    // Now the get-file call should return a library
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
    await workspace.clickLibrary("Testing library 1");
    await expect(workspace.librariesModal.getByText("Added")).toBeVisible();
    await expect(workspace.page).toHaveScreenshot();
  });

  test("Shows the assets correctly", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    const { fileId, pageId } = await setupFileWithAssets(workspace);

    await workspace.goToWorkspace({ fileId, pageId });

    await workspace.clickAssets();
    await workspace.sidebar.getByRole("button", { name: "Components" }).click();
    await workspace.sidebar.getByRole("button", { name: "Colors" }).click();
    await workspace.sidebar
      .getByRole("button", { name: "Typographies" })
      .click();

    await expect(workspace.page).toHaveScreenshot();

    await workspace.sidebar.getByTitle("List view").click();

    await expect(workspace.page).toHaveScreenshot();
  });
});

test.describe("Left header", () => {
  test("Shows the shared badge and the save status", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
    await workspace.mockRPC(
      "update-file?id=*",
      "workspace/update-file-create-rect.json",
    );
    await workspace.goToWorkspace({
      fileId: "c1249a66-fce0-8175-8004-7433fe4be8bc",
      pageId: "c1249a66-fce0-8175-8004-7433fe4be8bd",
    });

    const header = page.locator("header", { hasText: "Testing library 1" });
    await expect(header).toBeVisible();
    // The badge and the status dot are tiny: compare every pixel.
    await expect(header).toHaveScreenshot("left-header-shared.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await workspace.rectShapeButton.click();
    await workspace.clickWithDragViewportAt(128, 128, 200, 100);
    await expect(header.getByTitle("Saved")).toBeVisible();
    await page.mouse.move(640, 600);
    await expect(header).toHaveScreenshot("left-header-saved.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Palette", () => {
  test("Shows the bottom palette expanded and collapsed", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    const { fileId, pageId } = await setupFileWithAssets(workspace);

    await workspace.goToWorkspace({ fileId, pageId });

    await expect(workspace.page).toHaveScreenshot();

    await workspace.palette
      .getByRole("button", { name: "Typographies" })
      .click();
    await expect(
      workspace.palette.getByText("Source Sans Pro Regular"),
    ).toBeVisible();
    await expect(workspace.page).toHaveScreenshot();

    await workspace.palette
      .getByRole("button", { name: "Color Palette" })
      .click();
    await expect(
      workspace.palette.getByText(
        "There are no color styles in your library yet",
      ),
    ).toBeVisible();
  });
});

test.describe("Nudge modal", () => {
  test("Shows the nudge amount modal", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();

    await page.getByRole("button", { name: "Main menu" }).click();
    await page.locator("#file-menu-preferences").click();
    await page.locator("#file-menu-nudge").click();

    await expect(page.getByText("Nudge amount")).toBeVisible();
    await page.mouse.move(0, 0);
    await expect(workspace.page).toHaveScreenshot("nudge-modal.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Selected colors", () => {
  test("Shows the more colors button", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickToggableLayer("Board");
    await page.keyboard.press("Control+A");

    await expect(
      workspace.rightSidebar.getByRole("button", { name: "More colors" }),
    ).toBeVisible();
    // The button is a small part of the sidebar: compare every pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Imported SVG attributes", () => {
  test("Shows the attributes of an imported SVG shape", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9930.json");
    await workspace.goToWorkspace({
      fileId: "8f843b59-7fbb-81ce-8005-aa6d47ae3111",
      pageId: "fb9798e7-a547-80ae-8005-9ffda4a13e2c",
    });

    // The fourth "workspace" board holds the imported SVG groups.
    await workspace.clickToggableLayer("workspace", {}, 3);
    await workspace.clickToggableLayer("Canvas");
    await workspace.clickLeafLayer("Rectangle 760 (Stroke)");

    const section = workspace.rightSidebar.getByText("Imported SVG Attributes");
    await section.scrollIntoViewIfNeeded();
    await expect(section).toBeVisible();
    // The attributes are a small part of the sidebar: compare every pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Grid cell options", () => {
  test("Shows the grid cell options when selecting a cell", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9042.json");
    await workspace.mockRPC(
      "get-file-fragment?file-id=*&fragment-id=*",
      "workspace/get-file-fragment-9042.json",
    );
    await workspace.goToWorkspace({
      fileId: "af2494d0-39ba-8184-8005-230696f6df5c",
      pageId: "af2494d0-39ba-8184-8005-230696f6df5d",
    });

    await workspace.clickLeafLayer("Board");
    await page
      .getByTestId("inspect-layout")
      .getByRole("button", { name: "Edit grid" })
      .click();

    // Click the first cell of the grid editor.
    const cell = page.locator("g.cell-editor > rect").first();
    const box = await cell.boundingBox();
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);

    await expect(workspace.rightSidebar.getByText("Grid cell")).toBeVisible();
    // The cell options are a small part of the sidebar: compare every pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});
