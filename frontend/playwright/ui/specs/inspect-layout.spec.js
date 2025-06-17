import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

// Fix for https://tree.taiga.io/project/penpot/issue/9042
test("Bug 9042 - Measurement unit dropdowns for columns are cut off in grid layout edit mode", async ({
  page,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-9042.json");
  await workspacePage.mockRPC(
    "get-file-fragment?file-id=*&fragment-id=*",
    "workspace/get-file-fragment-9042.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "af2494d0-39ba-8184-8005-230696f6df5c",
    pageId: "af2494d0-39ba-8184-8005-230696f6df5d",
  });
  await workspacePage.clickLeafLayer("Board");
  await workspacePage.expectSelectedLayer("Board");

  const layoutContainer = workspacePage.page.getByTestId("inspect-layout");
  await layoutContainer.getByRole("button", { name: "Edit grid" }).click();
  const rowsContainer = workspacePage.page.getByTestId("inspect-layout-rows");
  await rowsContainer.click();

  await rowsContainer.getByText("FR").nth(2).click();
  await expect(rowsContainer.getByText("%")).toBeInViewport();
});

test("[Taiga #9116] Copy CSS background color in the selected format in the INSPECT tab", async ({
  page,
  context,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);
  await workspacePage.clickLeafLayer("Rectangle");

  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.click();

  const colorDropdown = workspacePage.page
    .getByRole("combobox")
    .getByText("HEX");
  await colorDropdown.click();

  const rgbaFormatButton = workspacePage.page.getByRole("option", {
    name: "RGBA",
  });
  await rgbaFormatButton.click();

  const copyColorButton = workspacePage.page.getByRole("button", {
    name: "Copy color",
  });
  await copyColorButton.click();

  const rgbaColorText = await page.evaluate(() =>
    navigator.clipboard.readText(),
  );
  expect(rgbaColorText).toContain("background: rgba(");
});

test("[Taiga #10630] [INSPECT] Style assets not being displayed on info tab", async ({
  page,
  context,
}) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();
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

  await workspacePage.clickColorPalette();
  await workspacePage.clickAssets();
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-library.json");
  await workspacePage.openLibrariesModal();
  await workspacePage.clickLibrary("Testing library 1");
  await workspacePage.closeLibrariesModal();

  await expect(
    workspacePage.palette.getByRole("button", { name: "test-color-187cd5" }),
  ).toBeVisible();

  await workspacePage.clickLayers();

  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);
  await workspacePage.clickLeafLayer("Rectangle");
  await workspacePage.palette
    .getByRole("button", { name: "test-color-187cd5" })
    .click();

  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.click();

  const colorLibraryName = workspacePage.page.getByTestId("color-library-name");

  await expect(colorLibraryName).toHaveText("test-color-187cd5");
});
