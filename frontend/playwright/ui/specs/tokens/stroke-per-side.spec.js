import { test, expect } from "@playwright/test";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { setupStrokePerSideFile, unfoldTokenType } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

// Accessible names of the design tab controls, from the translations.
const TOP = "Top (block start)";
const RIGHT = "Right (inline end)";
const BOTTOM = "Bottom (block end)";
const LEFT = "Left (inline start)";
const GLOBAL = "Stroke width";
const PER_SIDE_TOGGLE_LABEL = "Stroke per side";
const ONLY_FIRST =
  "Design tokens can only be applied to the first fill or stroke in the list.";
const MIXED = "Mixed";

const PER_SIDE_ON = { "stroke-per-side": true };

function strokeSection(page) {
  return page.getByTestId("right-sidebar").getByRole("region", {
    name: "Stroke section",
  });
}

function strokeRow(page, index = 0) {
  return strokeSection(page).getByLabel(`stroke-row-${index}`);
}

function perSideOptions(page) {
  return page.getByTestId("stroke.per-side-options");
}

async function prepareShape(workspace, page) {
  await workspace.layers.getByTestId("layer-row").nth(0).click();
  await page.getByTestId("add-stroke").click();
  await expect(strokeSection(page)).toBeVisible();
}

async function openTokensTab(page) {
  await page.getByRole("tab", { name: "Tokens" }).click();
}

async function unfoldStrokeWidth(page, workspace) {
  await openTokensTab(page);
  await unfoldTokenType(workspace.tokensSidebar, "stroke width");
}

test.describe("Tokens: stroke per side", () => {
  test("per-side toggle is hidden when the feature flag is off", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page);
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("workspace/get-file-layout-stroke-token-json");
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await prepareShape(workspace, page);

    await expect(page.getByTestId("stroke.per-side-toggle")).toHaveCount(0);
    await expect(perSideOptions(page)).toHaveCount(0);
  });

  test("per-side toggle is enabled for a board with the feature on", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);

    const toggle = page.getByTestId("stroke.per-side-toggle");
    await expect(toggle).toBeVisible();
    await expect(toggle).toBeEnabled();
    await expect(toggle).toHaveAccessibleName(PER_SIDE_TOGGLE_LABEL);
    await expect(perSideOptions(page)).toHaveCount(0);
  });

  test("clicking the toggle expands and collapses the side inputs", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);

    const toggle = page.getByTestId("stroke.per-side-toggle");
    await toggle.click();

    await expect(perSideOptions(page)).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("textbox", { name: TOP }),
    ).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("textbox", { name: RIGHT }),
    ).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("textbox", { name: BOTTOM }),
    ).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("textbox", { name: LEFT }),
    ).toBeVisible();

    await toggle.click();
    await expect(perSideOptions(page)).toHaveCount(0);
  });

  test("the per-side preference survives a reload", async ({ page }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);

    await page.getByTestId("stroke.per-side-toggle").click();
    await expect(perSideOptions(page)).toBeVisible();

    // update-profile-props triggers a profile refresh; the prop must stick.
    await page.reload();
    await workspace.waitForFirstRender();
    await prepareShape(workspace, page);

    await expect(perSideOptions(page)).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("textbox", { name: TOP }),
    ).toBeVisible();
  });

  test("applying a stroke width token from the panel covers every side", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click();

    const row = strokeRow(page);
    await expect(
      row
        .getByLabel(GLOBAL, { exact: true })
        .getByRole("button", { name: "width-big" }),
    ).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(4);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: TOP }),
    ).toHaveCount(0);
  });

  test("applying the global action from the token menu covers every side", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });
    await workspace.tokenContextMenuForToken.getByText("All").first().click();

    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(4);
  });

  test("a dimensions token targets every side through the menu", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await openTokensTab(page);
    await unfoldTokenType(workspace.tokensSidebar, "dimensions");
    await workspace.tokensSidebar
      .getByRole("button", { name: "dim.md" })
      .click({ button: "right" });

    const menu = workspace.tokenContextMenuForToken;
    const strokeWidthEntry = menu
      .getByRole("listitem")
      .filter({ hasText: "Stroke Width" });
    await strokeWidthEntry.hover();
    await strokeWidthEntry
      .getByRole("listitem")
      .filter({ hasText: "All" })
      .click();

    await expect(
      perSideOptions(page).getByRole("button", { name: "dim.md" }),
    ).toHaveCount(4);
  });

  test("applying a token to one side leaves the other sides untouched", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });

    const menu = workspace.tokenContextMenuForToken;
    await expect(menu.getByText("Top")).toBeVisible();
    await menu.getByText("Top").click();

    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(1);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: TOP }),
    ).toHaveCount(0);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: RIGHT }),
    ).toHaveCount(1);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: BOTTOM }),
    ).toHaveCount(1);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: LEFT }),
    ).toHaveCount(1);
  });

  test("editing one side does not change the other sides", async ({ page }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    const options = perSideOptions(page);
    const rightInput = options.getByRole("textbox", { name: RIGHT });
    const bottomInput = options.getByRole("textbox", { name: BOTTOM });
    const leftInput = options.getByRole("textbox", { name: LEFT });
    const rightBefore = await rightInput.inputValue();
    const bottomBefore = await bottomInput.inputValue();
    const leftBefore = await leftInput.inputValue();

    const topInput = options.getByRole("textbox", { name: TOP });
    await topInput.fill("8");
    await topInput.press("Enter");

    await expect(topInput).toHaveValue("8");
    await expect(rightInput).toHaveValue(rightBefore);
    await expect(bottomInput).toHaveValue(bottomBefore);
    await expect(leftInput).toHaveValue(leftBefore);

    // The uniform field cannot show a single width anymore.
    await expect(
      strokeRow(page).getByRole("textbox", { name: GLOBAL }),
    ).toHaveAttribute("placeholder", MIXED);
  });

  test("detaching a side token leaves the other sides applied", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click();

    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(4);

    await perSideOptions(page)
      .getByRole("button", { name: "Detach token" })
      .first()
      .click();

    await expect(
      perSideOptions(page).getByRole("textbox", { name: TOP }),
    ).toHaveCount(1);
    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(3);
  });

  test("applying a token to one side creates the stroke when missing", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await workspace.layers.getByTestId("layer-row").nth(0).click();

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });
    await workspace.tokenContextMenuForToken.getByText("Top").click();

    await expect(strokeSection(page)).toBeVisible();
    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(1);
    await expect(
      perSideOptions(page).getByRole("textbox", { name: RIGHT }),
    ).toHaveValue("0");
  });

  test("a token on one side overrides a global token on that side only", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click();

    await workspace.tokensSidebar
      .getByRole("button", { name: "width-small" })
      .click({ button: "right" });
    await workspace.tokenContextMenuForToken.getByText("Right").click();

    const options = perSideOptions(page);
    await expect(
      options.getByRole("button", { name: "width-big" }),
    ).toHaveCount(3);
    await expect(
      options.getByRole("button", { name: "width-small" }),
    ).toHaveCount(1);
  });

  test("selecting the token again on one side completes it on every side", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });
    await workspace.tokenContextMenuForToken.getByText("Top").click();

    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(1);

    // The uniform field is mixed; picking the same token there covers
    // every side instead of removing the partial application. The uniform
    // field is the first token control in the row.
    const row = strokeRow(page);
    await row.getByRole("button", { name: "Open token list" }).first().click();
    await row.getByRole("option", { name: "width-big" }).click();

    const options = perSideOptions(page);
    await expect(
      options.getByRole("button", { name: "width-big" }),
    ).toHaveCount(4);
    await expect(options.getByRole("textbox", { name: TOP })).toHaveCount(0);
  });

  test("clicking the token again removes it from every side", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    const chip = workspace.tokensSidebar.getByRole("button", {
      name: "width-big",
    });
    await chip.click();
    await expect(
      perSideOptions(page).getByRole("button", { name: "width-big" }),
    ).toHaveCount(4);

    await chip.click();
    const options = perSideOptions(page);
    await expect(
      options.getByRole("button", { name: "width-big" }),
    ).toHaveCount(0);
    await expect(options.getByRole("textbox", { name: TOP })).toHaveCount(1);
    await expect(options.getByRole("textbox", { name: RIGHT })).toHaveCount(1);
  });

  test("the token menu only shows per-side entries for eligible shapes", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);

    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });

    const menu = workspace.tokenContextMenuForToken;
    await expect(menu.getByText("All")).toBeVisible();
    await expect(menu.getByText("Top")).toBeVisible();
    await expect(menu.getByText("Right")).toBeVisible();
    await expect(menu.getByText("Bottom")).toBeVisible();
    await expect(menu.getByText("Left")).toBeVisible();
  });

  test("the token menu hides per-side entries when the flag is off", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page);
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("workspace/get-file-layout-stroke-token-json");
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await prepareShape(workspace, page);
    await unfoldStrokeWidth(page, workspace);
    await workspace.tokensSidebar
      .getByRole("button", { name: "width-big" })
      .click({ button: "right" });

    const menu = workspace.tokenContextMenuForToken;
    await expect(menu.getByText("Stroke Width").first()).toBeVisible();
    await expect(menu.getByText("Top")).toHaveCount(0);
    await expect(menu.getByText("Right")).toHaveCount(0);
  });

  test("other shape types do not offer per-side controls", async ({ page }) => {
    const workspace = await setupStrokePerSideFile(page);

    await workspace.selectToolFromFlyout(workspace, {
      triggerToolName: "Rectangle (R)",
      targetToolName: "Ellipse (E)",
    });
    await workspace.clickWithDragViewportAt(520, 100, 100, 100);
    await page.getByTestId("add-stroke").click();

    await expect(strokeSection(page)).toBeVisible();
    await expect(page.getByTestId("stroke.per-side-toggle")).toHaveCount(0);
  });

  test("token controls are disabled on the second stroke", async ({ page }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);

    // The add button stays in the section header, so a second stroke is
    // one more click.
    await page.getByTestId("add-stroke").click();

    const firstRow = strokeRow(page, 0);
    const secondRow = strokeRow(page, 1);
    await expect(firstRow).toBeVisible();
    await expect(secondRow).toBeVisible();

    await expect(
      firstRow.getByRole("button", { name: "Open token list" }),
    ).toBeEnabled();
    const disabledTokenButton = secondRow.getByRole("button", {
      name: ONLY_FIRST,
    });
    await expect(disabledTokenButton).toBeVisible();
    await expect(disabledTokenButton).toBeDisabled();
  });

  test("the colorpicker disables tokens on the second stroke", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page);
    await prepareShape(workspace, page);
    await page.getByTestId("add-stroke").click();

    await strokeRow(page, 1)
      .getByRole("button", { name: "#000000" })
      .first()
      .click();

    const colorpicker = page.getByTestId("colorpicker");
    await expect(colorpicker).toBeVisible();
    await expect(
      colorpicker.getByRole("button", { name: ONLY_FIRST }),
    ).toBeDisabled();
  });

  test("per-side is offered for a multi-selection of rectangles", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page);
    await workspace.clickToggableLayer("Board");
    await workspace.clickLeafLayer("Rectangle", {}, 0);
    await workspace.clickLeafLayer("Rectangle", { modifiers: ["Shift"] }, 1);
    await page.getByTestId("add-stroke").click();

    await expect(page.getByTestId("stroke.per-side-toggle")).toBeVisible();
  });
});

test.describe("Tokens: stroke per side (inspect)", () => {
  test("Inspect styles expose the four per-side border widths", async ({
    page,
  }) => {
    const workspace = await setupStrokePerSideFile(page, {
      profileProps: PER_SIDE_ON,
    });
    await prepareShape(workspace, page);

    const options = perSideOptions(page);
    for (const [name, value] of [
      [TOP, "2"],
      [RIGHT, "8"],
      [BOTTOM, "4"],
      [LEFT, "6"],
    ]) {
      const input = options.getByRole("textbox", { name });
      await input.fill(value);
      await input.press("Enter");
    }

    await page.getByRole("tab", { name: "Inspect" }).click();
    const panel = page
      .getByTestId("right-sidebar")
      .getByRole("article")
      .filter({ hasText: "Stroke" });

    const expectRow = async (term, value) => {
      const row = panel.getByTestId("property-row").filter({ hasText: term });
      await expect(row).toContainText(value);
    };

    await expectRow("Border block start width", "2px");
    await expectRow("Border inline end width", "8px");
    await expectRow("Border block end width", "4px");
    await expectRow("Border inline start width", "6px");
  });
});

test.describe("Tokens: stroke per side (non wasm)", () => {
  test("per-side toggle is disabled without the new renderer", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.mockConfigFlags([
      "enable-stroke-per-side",
      "enable-feature-token-input",
    ]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("workspace/get-file-layout-stroke-token-json");
    await workspace.goToWorkspace();

    await prepareShape(workspace, page);

    const toggle = page.getByTestId("stroke.per-side-toggle");
    await expect(toggle).toBeVisible();
    await expect(toggle).toBeDisabled();
    await expect(toggle).toHaveAccessibleName(
      /only available in the new render/,
    );
  });
});
