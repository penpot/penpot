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

test.describe("Color palette", () => {
  test("Shows the scroll arrows and the palette menu", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-many-colors.json",
    );
    await workspace.goToWorkspace({
      fileId: "c1249a66-fce0-8175-8004-7433fe4be8bc",
      pageId: "c1249a66-fce0-8175-8004-7433fe4be8bd",
    });

    await workspace.clickColorPalette();
    await workspace.palette.locator('[class*="palette-actions"]').click();
    await page.getByText("File library").click();
    await expect(
      workspace.palette.getByRole("button", { name: "color-01" }),
    ).toBeVisible();
    await page.mouse.move(640, 300);
    // The arrows are a small part of the palette: compare every pixel.
    await expect(workspace.palette).toHaveScreenshot("color-palette.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await workspace.palette.locator('[class*="right-arrow"]').click();
    await page.mouse.move(640, 300);
    await expect(workspace.palette).toHaveScreenshot(
      "color-palette-scrolled.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );

    await workspace.palette.locator('[class*="palette-actions"]').click();
    await expect(page.getByText("Recent colors")).toBeVisible();
    await page.mouse.move(640, 300);
    await expect(page).toHaveScreenshot("color-palette-menu.png", {
      clip: { x: 300, y: 400, width: 700, height: 320 },
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

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
    await expect(
      section.getByRole("button", { name: "Edit grid" }),
    ).toBeVisible();
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

test.describe("Grid editor", () => {
  test("Shows the track options button on the canvas", async ({ page }) => {
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
    // Tracks need 110px or more on screen to show their options button.
    await page.keyboard.press("Control+=");
    await page.keyboard.press("Control+=");
    await page
      .getByTestId("inspect-layout")
      .getByRole("button", { name: "Edit grid" })
      .click();

    const shotTrack = async () => {
      const track = page.getByTestId("grid-track-editor-wrapper").first();
      await track.hover();
      await expect(
        page.getByTestId("grid-track-options-btn").first(),
      ).toBeVisible();
      // Wait until the zoom animation leaves the track in place.
      let box = await track.boundingBox();
      await expect
        .poll(async () => {
          const prev = box;
          box = await track.boundingBox();
          return prev.x === box.x && prev.width === box.width;
        })
        .toBe(true);
      await expect(page).toHaveScreenshot({
        clip: {
          x: Math.floor(box.x) - 8,
          y: Math.floor(box.y) - 8,
          width: Math.ceil(box.width) + 16,
          height: Math.ceil(box.height) + 16,
        },
        maxDiffPixelRatio: 0,
        threshold: 0,
      });
    };

    // The track editors draw at a fixed screen size at any zoom.
    await shotTrack();
    await page.keyboard.press("Control+=");
    await shotTrack();
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
    await expect(
      section.getByRole("textbox", { name: /top padding/i }),
    ).toBeVisible();
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
    await expect(
      section.getByRole("textbox", { name: "Top margin" }),
    ).toBeVisible();
    await expect(section).toHaveScreenshot("margin-multiple.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Component options", () => {
  test("Shows the component pill of a copy", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickToggableLayer("Board");
    await workspace.clickToggableLayer("Group");
    await workspace.layers.getByText("A", { exact: true }).click();

    await expect(
      workspace.rightSidebar.getByTestId("component-pill-button"),
    ).toBeVisible();
    // The component pill is a small part of the sidebar: compare every
    // pixel.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });

  test("Shows the groups of the swap panel", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-9066-swap-groups.json",
    );
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickToggableLayer("Board");
    await workspace.clickToggableLayer("Group");
    await workspace.layers.getByText("A", { exact: true }).click();
    await workspace.rightSidebar.getByRole("button", { name: /^A / }).click();

    await expect(workspace.rightSidebar.getByText("Letters")).toBeVisible();
    await workspace.rightSidebar.getByText("Letters").hover();
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });

  test("Shows the annotation actions of a main component", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickToggableLayer("Board");
    await workspace.clickToggableLayer("Group");
    await workspace.layers.getByText("B", { exact: true }).click();
    await workspace.rightSidebar
      .getByRole("button", { name: "Options" })
      .click();
    await workspace.rightSidebar.getByText("Create annotation").click();

    // Creating: save (disabled while the text is empty) and discard.
    const annotation = workspace.rightSidebar.locator("#annotation-textarea");
    await expect(annotation).toBeFocused();
    await expect(workspace.rightSidebar).toHaveScreenshot(
      "annotation-creating.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );

    // Saved: edit and delete show when hovering the title.
    await annotation.fill("An annotation");
    await workspace.rightSidebar
      .getByRole("button", { name: "Create", exact: true })
      .click();
    const title = workspace.rightSidebar.getByText("Annotation", {
      exact: true,
    });
    await title.hover();
    await expect(workspace.rightSidebar).toHaveScreenshot(
      "annotation-saved.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );

    // Collapsed by clicking its title.
    await title.click();
    await expect(
      workspace.rightSidebar.getByText("An annotation"),
    ).toBeHidden();
    await expect(workspace.rightSidebar).toHaveScreenshot(
      "annotation-collapsed.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );
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

test.describe("Debug shape panel", () => {
  test("Shows the debug info of the selected shape", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickLeafLayer("Board");
    await page.getByRole("button", { name: "Debugging tool" }).click();
    await page.getByText("shape-panel", { exact: true }).click();
    await expect(
      workspace.rightSidebar.getByText("Board", { exact: true }),
    ).toBeVisible();
    await page.mouse.move(640, 600);
    // Only the title row with the dump buttons: the attributes list below
    // holds ids that change on every run.
    const title = workspace.rightSidebar
      .getByText("Board", { exact: true })
      .locator("..");
    await expect(title).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Layers panel", () => {
  test("Shows the focus mode title and the layer filters", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickLeafLayer("Board");
    await page.keyboard.press("f");
    await expect(workspace.sidebar.getByText("Focus mode")).toBeVisible();
    await page.mouse.move(640, 600);
    await expect(workspace.sidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await page.keyboard.press("f");
    await expect(workspace.sidebar.getByText("Focus mode")).toBeHidden();
    await workspace.sidebar.getByRole("button", { name: "Search" }).click();
    await workspace.sidebar.getByRole("button", { name: "Filter" }).click();
    await workspace.sidebar.getByText("Boards", { exact: true }).click();
    await page.mouse.move(640, 600);
    // The "Boards" filter pill. The clip leaves out the pages divider and
    // the search bar, whose transitions may still be running.
    const filtersClip = { x: 0, y: 340, width: 318, height: 380 };
    await expect(page).toHaveScreenshot({
      clip: filtersClip,
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await workspace.sidebar.getByRole("button", { name: "Filter" }).click();
    await page.mouse.move(640, 600);
    // The filter menu open, with its option ticked.
    await expect(page).toHaveScreenshot({
      clip: filtersClip,
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Guides", () => {
  test("Shows the advanced options of a square guide", async ({ page }) => {
    // A taller window keeps the whole Design tab visible: a scrolled
    // sidebar can land on a fractional offset and change the antialiasing.
    await page.setViewportSize({ width: 1280, height: 900 });
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();

    await workspace.boardButton.click();
    await workspace.clickWithDragViewportAt(128, 128, 200, 200);
    await workspace.rightSidebar
      .getByRole("button", { name: "Add guide" })
      .click();

    // The advanced options toggle has no accessible name yet.
    await workspace.rightSidebar
      .locator("button[class*='frame_grid__show-options']")
      .click();
    await expect(workspace.rightSidebar.getByText("Color")).toBeVisible();
    // The guide options are a small part of the sidebar: compare every
    // pixel. The color rows antialias their rounded corners one color step
    // differently between runs, so allow that much and nothing more.
    await expect(workspace.rightSidebar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0.02,
    });
  });
});

test.describe("Viewport top bar", () => {
  test("Shows the view only bar of the inspect tab", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(/get\-file\?/, "workspace/get-file-9066.json");
    await workspace.goToWorkspace({
      fileId: "e179d9df-de35-80bf-8005-283bbd5516b0",
      pageId: "e179d9df-de35-80bf-8005-283bbd5516b1",
    });

    await workspace.clickLeafLayer("Board");
    await workspace.rightSidebar.getByRole("tab", { name: "Inspect" }).click();
    const bar = page.getByText("Inspecting code").locator("../../..");
    await expect(bar).toBeVisible();
    await page.mouse.move(640, 600);
    await expect(bar).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
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

test.describe("Right header", () => {
  test("Shows the header actions and the zoom menu", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();

    const header = page.locator('[class*="workspace-header-right"]');
    await expect(header).toBeVisible();
    await page.mouse.move(640, 300);
    // The buttons are a small part of the header: compare every pixel.
    await expect(header).toHaveScreenshot("right-header.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await header.getByRole("button", { name: /Comments/ }).click();
    await page.mouse.move(640, 300);
    await expect(header).toHaveScreenshot("right-header-comments.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
    await page.keyboard.press("Escape");

    await header.getByTitle("Zoom").click();
    await expect(page.getByText("Reset")).toBeVisible();
    await page.mouse.move(640, 300);
    await expect(page).toHaveScreenshot("right-header-zoom.png", {
      clip: { x: 900, y: 0, width: 380, height: 260 },
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Undo history", () => {
  test("Shows the actions list with an entry expanded", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      "update-file?id=*",
      "workspace/update-file-empty.json",
    );
    await workspace.mockRPC(
      "get-file-snapshots?file-id=*",
      "workspace/versions-snapshot-1.json",
    );
    await workspace.goToWorkspace();

    await page.keyboard.press("r");
    await workspace.clickWithDragViewportAt(128, 128, 200, 100);

    await page.keyboard.press("Control+Alt+h");
    await page.getByRole("tab", { name: "Actions" }).click();
    const panel = page.getByRole("tabpanel", { name: "Actions" });
    const entry = panel.getByText("New rectangle");
    // The short id and the relative time change on every run.
    const mask = [page.locator('[class*="history-entry-meta"]')];

    // The expand arrow only shows while the entry is hovered.
    await entry.hover();
    await expect(panel).toHaveScreenshot({ mask });

    // The data attribute exists before and after the DS migration.
    await panel.locator('[data-has-entry="true"]').click();
    await expect(panel.getByText("Rectangle", { exact: true })).toBeVisible();
    await entry.hover();
    await expect(panel).toHaveScreenshot({ mask });
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

  test("Shows the palette toggles selected", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    const { fileId, pageId } = await setupFileWithAssets(workspace);

    await workspace.goToWorkspace({ fileId, pageId });

    await workspace.palette
      .getByRole("button", { name: "Typographies" })
      .click();
    await expect(
      workspace.palette.getByText("Source Sans Pro Regular"),
    ).toBeVisible();
    await page.mouse.move(640, 300);
    // The toggles are a small part of the palette: compare every pixel.
    await expect(workspace.palette).toHaveScreenshot("palette-text.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await workspace.palette
      .getByRole("button", { name: "Color Palette" })
      .click();
    await expect(
      workspace.palette.getByText(
        "There are no color styles in your library yet",
      ),
    ).toBeVisible();
    await page.mouse.move(640, 300);
    await expect(workspace.palette).toHaveScreenshot("palette-color.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});

test.describe("Plugins", () => {
  const setupPlugins = async (workspace, profile) => {
    await workspace.setupEmptyFile();
    await workspace.mockRPC("get-team?id=*", "workspace/get-team-plugins.json");
    if (profile) {
      await workspace.mockRPC("get-profile", profile);
    }
    await workspace.goToWorkspace();
    await workspace.page.keyboard.press("Control+Alt+P");
  };

  test("Shows the plugins manager without plugins", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await setupPlugins(workspace);

    await expect(page.getByText("No plugins installed yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Close" })).toBeVisible();
    await expect(page).toHaveScreenshot();
  });

  test("Shows the plugins manager and the permissions dialog", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await page.route("https://plugin.example.com/manifest.json", (route) =>
      route.fulfill({
        json: {
          name: "Contrast checker",
          description: "Check the contrast of the selected layers.",
          code: "plugin.js",
          permissions: ["content:write", "user:read", "library:write"],
        },
      }),
    );
    await setupPlugins(
      workspace,
      "logged-in-user/get-profile-with-plugins.json",
    );

    await expect(page.getByText("Lorem Ipsum generator")).toBeVisible();
    await expect(page).toHaveScreenshot();

    await page
      .getByRole("textbox")
      .fill("https://plugin.example.com/manifest.json");
    await page.getByRole("button", { name: "Install" }).click();
    await expect(page.getByText("CONTRAST CHECKER")).toBeVisible();
    await expect(page).toHaveScreenshot();
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

test.describe("Text palette", () => {
  test("Shows the scroll arrows and the palette menu", async ({ page }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-many-typographies.json",
    );
    await workspace.goToWorkspace({
      fileId: "c1249a66-fce0-8175-8004-7433fe4be8bc",
      pageId: "c1249a66-fce0-8175-8004-7433fe4be8bd",
    });

    await workspace.palette
      .getByRole("button", { name: "Typographies" })
      .click();
    await expect(
      workspace.palette.getByText("Type 01 Regular 12"),
    ).toBeVisible();
    await page.mouse.move(640, 300);
    // The arrows are a small part of the palette: compare every pixel.
    await expect(workspace.palette).toHaveScreenshot("text-palette.png", {
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    const rightArrow = workspace.palette.locator('[class*="right-arrow"]');
    await rightArrow.hover();
    await expect(workspace.palette).toHaveScreenshot(
      "text-palette-arrow-hover.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );

    await rightArrow.click();
    await page.mouse.move(640, 300);
    await expect(workspace.palette).toHaveScreenshot(
      "text-palette-scrolled.png",
      { maxDiffPixelRatio: 0, threshold: 0 },
    );

    await workspace.palette.locator('[class*="palette-actions"]').click();
    await expect(page.getByText("File library")).toBeVisible();
    await page.mouse.move(640, 300);
    await expect(page).toHaveScreenshot("text-palette-menu.png", {
      clip: { x: 300, y: 450, width: 700, height: 270 },
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

test.describe("Export modal", () => {
  test("Shows the export frames modal with every selection state", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-export-frames.json",
    );
    await workspace.mockRPC(
      "get-file-object-thumbnails?file-id=*",
      "workspace/get-file-object-thumbnails-blank.json",
    );
    await workspace.mockRPC(
      "get-file-fragment?file-id=*",
      "workspace/get-file-fragment-blank.json",
    );
    await workspace.goToWorkspace({
      fileId: "8d102c33-b98f-81f6-8006-fdd4ea9780d7",
      pageId: "8d102c33-b98f-81f6-8006-fdd4ea9780d8",
    });

    await page.getByRole("button", { name: "Main menu" }).click();
    await page.getByText("file").last().click();
    await page.locator("#file-menu-export-frames").click();
    const modal = page.getByText("Export as PDF").locator("../..");
    await expect(page.getByText("2 of 2 elements selected")).toBeVisible();
    await page.mouse.move(0, 0);
    // Every row checked.
    await expect(modal).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await page.getByRole("button", { name: "Board 1" }).click();
    await expect(page.getByText("1 of 2 elements selected")).toBeVisible();
    await page.mouse.move(0, 0);
    // Mixed: the header checkbox shows the "some selected" mark.
    await expect(modal).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });

    await page.getByRole("button", { name: "Board 2" }).click();
    await expect(page.getByText("0 of 2 elements selected")).toBeVisible();
    await page.mouse.move(0, 0);
    // Nothing checked: the export button is disabled.
    await expect(modal).toHaveScreenshot({
      maxDiffPixelRatio: 0,
      threshold: 0,
    });
  });
});
