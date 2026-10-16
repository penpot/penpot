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
