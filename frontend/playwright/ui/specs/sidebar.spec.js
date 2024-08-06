import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test.describe("Layers tab", () => {
  test("BUG 7466 - Layers tab height extends to the bottom when 'Pages' is collapsed", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();

    await workspace.goToWorkspace();

    const { height: heightExpanded } = await workspace.layers.boundingBox();
    await workspace.togglePages();
    const { height: heightCollapsed } = await workspace.layers.boundingBox();

    expect(heightExpanded > heightCollapsed);
  });
});

test.describe("Assets tab", () => {
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
    await workspacePage.mockRPC(
      /get\-file\?/,
      "workspace/get-file-library.json",
    );
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
});
