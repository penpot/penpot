import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

// Fix for https://tree.taiga.io/project/penpot/issue/8593 and
// https://tree.taiga.io/project/penpot/issue/9042
test("The grid layout editor shows the track options and its unit dropdown", async ({
  page,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
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

  // The options button is only rendered with a track of 110 px or more
  // (`medium-size-limit` in grid_layout_editor.cljs) and the board tracks are
  // 100 px wide, so two zoom steps take them over the limit. That is the
  // condition the assertions below wait for, so no sleep is needed.
  await page.keyboard.press("Control+=");
  await page.keyboard.press("Control+=");

  // "Edit grid" toggles the editor, so it is clicked once for both cases.
  await page
    .getByTestId("inspect-layout")
    .getByRole("button", { name: "Edit grid" })
    .click();

  await test.step("hovering a track shows its options button", async () => {
    // The button lives in the DOM and CSS :hover shows it, which works in every
    // browser; before it was only rendered from the JS hover state (broken in
    // WebKit). It is hidden with `opacity: 0`, and Playwright's `toBeVisible`
    // ignores opacity, so the assertions read the computed value instead.
    const gridEditorButton = page.getByTestId("grid-track-options-btn").first();
    await expect(gridEditorButton).toBeAttached();
    await expect(gridEditorButton).toHaveCSS("opacity", "0");

    const gridEditorWrapper = page
      .getByTestId("grid-track-editor-wrapper")
      .first();
    await gridEditorWrapper.hover();
    await expect(gridEditorButton).toHaveCSS("opacity", "1");
  });

  await test.step("the unit dropdown is not cut off", async () => {
    const rowsContainer = page.getByTestId("inspect-layout-rows");
    await rowsContainer.click();

    await rowsContainer.getByText("FR").nth(2).click();
    await expect(rowsContainer.getByText("%")).toBeInViewport();
  });
});

test("[Taiga #9116] Copy CSS background color in the selected format in the INSPECT tab", async ({
  page,
  context,
}) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile(page);
  await workspacePage.goToWorkspace();

  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);
  await workspacePage.clickLeafLayer("Rectangle");

  const inspectButton = workspacePage.page.getByRole("tab", {
    name: "Inspect",
  });
  await inspectButton.click();

  // Open color space selector combobox and change to RGBA format
  const colorDropdown = workspacePage.page
    .getByRole("combobox")
    .getByText("HEX");
  await colorDropdown.click();

  const rgbaFormatButton = workspacePage.page.getByRole("option", {
    name: "RGBA",
  });
  await rgbaFormatButton.click();

  // Open info tab selector and select the computed tab
  const infoTabSelector = workspacePage.page
    .getByRole("combobox")
    .getByText("Styles");
  await infoTabSelector.click();

  const infoTabSelectorButton = workspacePage.page.getByRole("option", {
    name: "Computed",
  });
  await infoTabSelectorButton.click();

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
  const workspacePage = new WasmWorkspacePage(page);
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
  await workspacePage.librariesModal
    .getByRole("tab", { name: "Libraries" })
    .click();
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

  // Open info tab selector and select the computed tab
  const infoTabSelector = workspacePage.page
    .getByRole("combobox")
    .getByText("Styles");
  await infoTabSelector.click();

  const infoTabSelectorButton = workspacePage.page.getByRole("option", {
    name: "Computed",
  });
  await infoTabSelectorButton.click();

  const colorLibraryName = workspacePage.page.getByTestId("color-library-name");

  await expect(colorLibraryName).toHaveText("test-color-187cd5");
});
