import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import {
  setupEmptyTokensFile,
  setupTokensFile,
  setupTypographyTokensFile,
  unfoldTokenTree,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens: Apply token", () => {
  test("User applies color token to a shape", async ({ page }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers
      .getByTestId("layer-row")
      .filter({ hasText: "Button" })
      .click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    unfoldTokenTree(tokensSidebar, "color", "colors.black");

    await tokensSidebar
      .getByRole("button", { name: "black" })
      .click({ button: "right" });
    await tokenContextMenuForToken.getByText("Fill").click();

    await expect(
      workspacePage.page.getByLabel("Name: colors.black"),
    ).toBeVisible();
  });

  test("User applies border-radius token to a shape from sidebar", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    // Open tokens sections on left sidebar
    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    // Unfold border radius tokens
    await page.getByRole("button", { name: "Border Radius 3" }).click();
    await expect(
      tokensSidebar.getByRole("button", { name: "borderRadius" }),
    ).toBeVisible();
    await tokensSidebar.getByRole("button", { name: "borderRadius" }).click();
    await expect(
      tokensSidebar.getByRole("button", { name: "borderRadius.sm" }),
    ).toBeVisible();

    // Apply border radius token from token panels
    await tokensSidebar
      .getByRole("button", { name: "borderRadius.sm" })
      .click();

    // Check if border radius sections is visible on right sidebar
    const borderRadiusSection = page.getByRole("region", {
      name: "border-radius-section",
    });
    await expect(borderRadiusSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const brTokenPillSM = borderRadiusSection.getByRole("button", {
      name: "borderRadius.sm",
    });
    await expect(brTokenPillSM).toBeVisible();
    await brTokenPillSM.click();

    // Change token from dropdown
    const brTokenOptionXl = borderRadiusSection.getByLabel("borderRadius.xl");
    await expect(brTokenOptionXl).toBeVisible();
    await brTokenOptionXl.click();

    await expect(brTokenPillSM).not.toBeVisible();
    const brTokenPillXL = borderRadiusSection.getByRole("button", {
      name: "borderRadius.xl",
    });
    await expect(brTokenPillXL).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = borderRadiusSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.click();
    await expect(brTokenPillXL).not.toBeVisible();
  });

  test("User applies opacity token to a shape from sidebar", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    // Open tokens sections on left sidebar
    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    // Unfold opacity tokens
    await page.getByRole("button", { name: "Opacity 3" }).click();
    await expect(
      tokensSidebar.getByRole("button", { name: "opacity", exact: true }),
    ).toBeVisible();
    await tokensSidebar
      .getByRole("button", { name: "opacity", exact: true })
      .click();
    await expect(
      tokensSidebar.getByRole("button", { name: "opacity.high" }),
    ).toBeVisible();

    // Apply opacity token from token panels
    await tokensSidebar.getByRole("button", { name: "opacity.high" }).click();

    // Check if opacity sections is visible on right sidebar
    const layerMenuSection = page.getByRole("region", {
      name: "layer-menu-section",
    });
    await expect(layerMenuSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const opacityHighPill = layerMenuSection.getByRole("button", {
      name: "opacity.high",
    });
    await expect(opacityHighPill).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = layerMenuSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.click();

    // Open dropdown from input
    const dropdownBtn = layerMenuSection.getByLabel("Open token list");
    await expect(dropdownBtn).toBeVisible();
    await dropdownBtn.click();

    // Change token from dropdown
    const opacityLowOption = layerMenuSection.getByRole("option", {
      name: "opacity.low",
    });
    await expect(opacityLowOption).toBeVisible();
    await opacityLowOption.click();

    await expect(opacityHighPill).not.toBeVisible();
    const opacityLowPill = layerMenuSection.getByRole("button", {
      name: "opacity.low",
    });
    await expect(opacityLowPill).toBeVisible();
  });

  test("User applies typography token to a text shape", async ({ page }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTypographyTokensFile(page);

    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers
      .getByTestId("layer-row")
      .filter({ hasText: "Some Text" })
      .click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    await tokensSidebar
      .getByRole("button")
      .filter({ hasText: "Typography" })
      .click();

    await tokensSidebar.getByRole("button", { name: "Full" }).click();

    const fontSizeInput = workspacePage.rightSidebar.getByRole("textbox", {
      name: "Font Size",
    });
    await expect(fontSizeInput).toBeVisible();
    await expect(fontSizeInput).toHaveValue("100");
  });

  test("User adds shadow token with multiple shadows and applies it to shape", async ({
    page,
  }) => {
    const {
      tokensUpdateCreateModal,
      tokensSidebar,
      workspacePage,
      tokenContextMenuForToken,
    } = await setupTokensFile(page, { flags: ["enable-token-shadow"] });

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    await test.step("Stage 1: Basic open", async () => {
      // User adds shadow via the sidebar
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Shadow" })
        .click();

      await expect(tokensUpdateCreateModal).toBeVisible();

      const nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("primary");

      // User adds first shadow with a color from the color ramp
      const firstShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-0",
      );
      await expect(firstShadowFields).toBeVisible();

      // Fill in the shadow values
      const offsetXInput = firstShadowFields.getByLabel("X");
      const offsetYInput = firstShadowFields.getByLabel("Y");
      const blurInput = firstShadowFields.getByRole("textbox", {
        name: "Blur",
      });
      const spreadInput = firstShadowFields.getByRole("textbox", {
        name: "Spread",
      });

      await offsetXInput.fill("2");
      await offsetYInput.fill("2");
      await blurInput.fill("4");
      await spreadInput.fill("0");

      // Add color using the color picker
      const colorBullet = firstShadowFields.getByTestId(
        "token-form-color-bullet",
      );
      await colorBullet.click();

      // Click on the color ramp to select a color
      const valueSaturationSelector = tokensUpdateCreateModal.getByTestId(
        "value-saturation-selector",
      );
      await expect(valueSaturationSelector).toBeVisible();
      await valueSaturationSelector.click({ position: { x: 50, y: 50 } });

      // Verify that a color value was set
      const colorInput = firstShadowFields.getByRole("textbox", {
        name: "Color",
      });
      await expect(colorInput).toHaveValue(/^rgb(.*)$/);

      // Wait for validation to complete
      await expect(
        tokensUpdateCreateModal.getByText(/Resolved value:/).first(),
      ).toBeVisible();

      // Save button should be enabled
      const submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await expect(submitButton).toBeEnabled();
    });

    await test.step("Stage 2: Shadow adding/removing works", async () => {
      const firstShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-0",
      );
      const colorInput = firstShadowFields.getByRole("textbox", {
        name: "Color",
      });
      const firstColorValue = await colorInput.inputValue();

      // User adds a second shadow
      const addButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Add Shadow",
      });
      await addButton.click();

      const secondShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-1",
      );
      await expect(secondShadowFields).toBeVisible();

      // User adds a third shadow
      await addButton.click();

      const thirdShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-2",
      );
      await expect(thirdShadowFields).toBeVisible();

      // User adds values for the third shadow
      const thirdOffsetXInput = thirdShadowFields.getByLabel("X");
      const thirdOffsetYInput = thirdShadowFields.getByLabel("Y");
      const thirdBlurInput = thirdShadowFields.getByRole("textbox", {
        name: "Blur",
      });
      const thirdSpreadInput = thirdShadowFields.getByRole("textbox", {
        name: "Spread",
      });
      const thirdColorInput = thirdShadowFields.getByRole("textbox", {
        name: "Color",
      });

      await thirdOffsetXInput.fill("10");
      await thirdOffsetYInput.fill("10");
      await thirdBlurInput.fill("20");
      await thirdSpreadInput.fill("5");
      await thirdColorInput.fill("#FF0000");

      // User removes the 2nd shadow
      const removeButton2 = secondShadowFields.getByRole("button", {
        name: "Remove Shadow",
      });
      await removeButton2.click();

      // Verify that we have only two shadow fields
      await expect(thirdShadowFields).not.toBeVisible();

      // Verify that the first shadow kept its values
      const firstOffsetXValue = await firstShadowFields
        .getByLabel("X")
        .inputValue();
      const firstOffsetYValue = await firstShadowFields
        .getByLabel("Y")
        .inputValue();
      const firstBlurValue = await firstShadowFields
        .getByRole("textbox", { name: "Blur" })
        .inputValue();
      const firstSpreadValue = await firstShadowFields
        .getByRole("textbox", { name: "Spread" })
        .inputValue();
      const firstColorValueAfter = await firstShadowFields
        .getByRole("textbox", { name: "Color" })
        .inputValue();

      await expect(firstOffsetXValue).toBe("2");
      await expect(firstOffsetYValue).toBe("2");
      await expect(firstBlurValue).toBe("4");
      await expect(firstSpreadValue).toBe("0");
      await expect(firstColorValueAfter).toBe(firstColorValue);

      // Verify that the second kept its values (after shadow 3)
      // After removing index 1, the third shadow becomes the second shadow at index 1
      const newSecondShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-1",
      );
      await expect(newSecondShadowFields).toBeVisible();

      const secondOffsetXValue = await newSecondShadowFields
        .getByLabel("X")
        .inputValue();
      const secondOffsetYValue = await newSecondShadowFields
        .getByLabel("Y")
        .inputValue();
      const secondBlurValue = await newSecondShadowFields
        .getByRole("textbox", { name: "Blur" })
        .inputValue();
      const secondSpreadValue = await newSecondShadowFields
        .getByRole("textbox", { name: "Spread" })
        .inputValue();
      const secondColorValue = await newSecondShadowFields
        .getByRole("textbox", { name: "Color" })
        .inputValue();

      await expect(secondOffsetXValue).toBe("10");
      await expect(secondOffsetYValue).toBe("10");
      await expect(secondBlurValue).toBe("20");
      await expect(secondSpreadValue).toBe("5");
      await expect(secondColorValue).toBe("#FF0000");
    });

    await test.step("Stage 3: Restore when switching tabs works", async () => {
      const firstShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-0",
      );
      const newSecondShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-1",
      );
      const colorInput = firstShadowFields.getByRole("textbox", {
        name: "Color",
      });
      const firstColorValue = await colorInput.inputValue();

      // Switch to reference tab
      const referenceTabButton =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceTabButton.click();

      // Verify we're in reference mode - the composite fields should not be visible
      await expect(firstShadowFields).not.toBeVisible();

      // Switch back to composite tab
      const compositeTabButton =
        tokensUpdateCreateModal.getByTestId("composite-opt");
      await compositeTabButton.click();

      // Verify that shadows are restored
      await expect(firstShadowFields).toBeVisible();
      await expect(newSecondShadowFields).toBeVisible();

      // Verify first shadow values are still there
      const restoredFirstOffsetX = await firstShadowFields
        .getByLabel("X")
        .inputValue();
      const restoredFirstOffsetY = await firstShadowFields
        .getByLabel("Y")
        .inputValue();
      const restoredFirstBlur = await firstShadowFields
        .getByRole("textbox", { name: "Blur" })
        .inputValue();
      const restoredFirstSpread = await firstShadowFields
        .getByRole("textbox", { name: "Spread" })
        .inputValue();
      const restoredFirstColor = await firstShadowFields
        .getByRole("textbox", { name: "Color" })
        .inputValue();

      await expect(restoredFirstOffsetX).toBe("2");
      await expect(restoredFirstOffsetY).toBe("2");
      await expect(restoredFirstBlur).toBe("4");
      await expect(restoredFirstSpread).toBe("0");
      await expect(restoredFirstColor).toBe(firstColorValue);

      // Verify second shadow values are still there
      const restoredSecondOffsetX = await newSecondShadowFields
        .getByLabel("X")
        .inputValue();
      const restoredSecondOffsetY = await newSecondShadowFields
        .getByLabel("Y")
        .inputValue();
      const restoredSecondBlur = await newSecondShadowFields
        .getByRole("textbox", { name: "Blur" })
        .inputValue();
      const restoredSecondSpread = await newSecondShadowFields
        .getByRole("textbox", { name: "Spread" })
        .inputValue();
      const restoredSecondColor = await newSecondShadowFields
        .getByRole("textbox", { name: "Color" })
        .inputValue();

      await expect(restoredSecondOffsetX).toBe("10");
      await expect(restoredSecondOffsetY).toBe("10");
      await expect(restoredSecondBlur).toBe("20");
      await expect(restoredSecondSpread).toBe("5");
      await expect(restoredSecondColor).toBe("#FF0000");
    });

    await test.step("Stage 4: Layer application works", async () => {
      // Save the token
      const submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      unfoldTokenTree(tokensSidebar, "shadow", "primary");

      // Verify token appears in sidebar
      const shadowToken = tokensSidebar.getByRole("button", {
        name: "primary",
      });
      await expect(shadowToken).toBeEnabled();

      // Apply the shadow
      await workspacePage.clickLayers();
      await workspacePage.clickLeafLayer("Button");

      const shadowSection = workspacePage.rightSidebar.getByText("Drop shadow");
      await expect(shadowSection).toHaveCount(0);

      await page.getByRole("tab", { name: "Tokens" }).click();
      await shadowToken.click();

      await expect(shadowSection).toHaveCount(2);
    });
  });

  test("User applies dimension token to a shape on width and height", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    // Unfolds dimensions on token panel
    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    unfoldTokenTree(tokensSidebar, "dimensions", "dimension.dimension.sm");

    // Apply token to width and height token from token panel
    await tokensSidebar.getByRole("button", { name: "dimension.sm" }).click();

    // Check if measures sections is visible on right sidebar
    const measuresSection = page.getByRole("region", {
      name: "shape-measures-section",
    });
    await expect(measuresSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const dimensionSMTokenPill = measuresSection.getByRole("button", {
      name: "dimension.sm",
    });
    await expect(dimensionSMTokenPill).toHaveCount(2);
    await dimensionSMTokenPill.nth(1).click();

    // Change token from dropdown
    const dimensionTokenOptionXl = measuresSection.getByLabel("dimension.xl");
    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    await expect(dimensionSMTokenPill).toHaveCount(1);
    const dimensionXLTokenPill = measuresSection.getByRole("button", {
      name: "dimension.xl",
    });
    await expect(dimensionXLTokenPill).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = measuresSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.nth(1).click();
    await expect(dimensionXLTokenPill).not.toBeVisible();
  });

  test("User applies dimension token to a shape on x position", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    // Unfolds dimensions on token panel
    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    unfoldTokenTree(tokensSidebar, "dimensions", "dimension.dimension.sm");

    // Apply token to width and height token from token panel
    await tokensSidebar
      .getByRole("button", { name: "dimension.sm" })
      .click({ button: "right" });
    await tokenContextMenuForToken.getByText("AxisX").click();

    // Check if measures sections is visible on right sidebar
    const measuresSection = page.getByRole("region", {
      name: "shape-measures-section",
    });
    await expect(measuresSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const dimensionSMTokenPill = measuresSection.getByRole("button", {
      name: "dimension.sm",
    });
    await expect(dimensionSMTokenPill).toBeVisible();
    await dimensionSMTokenPill.click();

    // Change token from dropdown
    const dimensionTokenOptionXl = measuresSection.getByLabel("dimension.xl");
    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    await expect(dimensionSMTokenPill).not.toBeVisible();
    const dimensionXLTokenPill = measuresSection.getByRole("button", {
      name: "dimension.xl",
    });
    await expect(dimensionXLTokenPill).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = measuresSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.nth(0).click();
    await expect(dimensionXLTokenPill).not.toBeVisible();
  });

  test("User applies dimension token to a shape on y position", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    // Unfolds dimensions on token panel
    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    unfoldTokenTree(tokensSidebar, "dimensions", "dimension.dimension.sm");

    // Apply token to width and height token from token panel
    await tokensSidebar
      .getByRole("button", { name: "dimension.sm" })
      .click({ button: "right" });
    await tokenContextMenuForToken.getByText("Y").click();

    // Check if measures sections is visible on right sidebar
    const measuresSection = page.getByRole("region", {
      name: "shape-measures-section",
    });
    await expect(measuresSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const dimensionSMTokenPill = measuresSection.getByRole("button", {
      name: "dimension.sm",
    });
    await expect(dimensionSMTokenPill).toBeVisible();
    await dimensionSMTokenPill.click();

    // Change token from dropdown
    const dimensionTokenOptionXl = measuresSection.getByLabel("dimension.xl");
    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    await expect(dimensionSMTokenPill).not.toBeVisible();
    const dimensionXLTokenPill = measuresSection.getByRole("button", {
      name: "dimension.xl",
    });
    await expect(dimensionXLTokenPill).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = measuresSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.nth(0).click();
    await expect(dimensionXLTokenPill).not.toBeVisible();
  });

  test("User applies dimension token to a shape border-radius", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    // Unfolds dimensions on token panel
    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(2).click();

    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    unfoldTokenTree(tokensSidebar, "dimensions", "dimension.dimension.xs");

    // Apply token to width and height token from token panel
    await tokensSidebar
      .getByRole("button", { name: "dimension.xs" })
      .click({ button: "right" });
    await tokenContextMenuForToken.getByText("Border radius").hover();
    await tokenContextMenuForToken.getByText("RadiusAll").click();

    // Check if border radius sections is visible on right sidebar
    const borderRadiusSection = page.getByRole("region", {
      name: "border-radius-section",
    });
    await expect(borderRadiusSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const dimensionXSTokenPill = borderRadiusSection.getByRole("button", {
      name: "dimension.xs",
    });
    await expect(dimensionXSTokenPill).toBeVisible();
    await dimensionXSTokenPill.click();

    // Change token from dropdown
    const dimensionTokenOptionXl =
      borderRadiusSection.getByLabel("dimension.xl");
    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    await expect(dimensionXSTokenPill).not.toBeVisible();
    const dimensionXLTokenPill = borderRadiusSection.getByRole("button", {
      name: "dimension.xl",
    });
    await expect(dimensionXLTokenPill).toBeVisible();

    // Detach token from design tab on right sidebar
    const detachButton = borderRadiusSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.nth(0).click();
    await expect(dimensionXLTokenPill).not.toBeVisible();
  });

  test("User applies stroke width token to a shape", async ({ page }) => {
    const workspace = new WorkspacePage(page, {
      textEditor: true,
    });
    // Set up
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("workspace/get-file-layout-stroke-token-json");
    await workspace.goToWorkspace();

    // Select shape apply stroke
    await workspace.layers.getByTestId("layer-row").nth(0).click();
    const rightSidebar = page.getByTestId("right-sidebar");
    await expect(rightSidebar).toBeVisible();
    await rightSidebar.getByTestId("add-stroke").click();

    // Apply stroke width token from token panel
    const tokensTab = page.getByRole("tab", { name: "Tokens" });
    await expect(tokensTab).toBeVisible();
    await tokensTab.click();
    await page.getByRole("button", { name: "Stroke Width 2" }).click();
    const tokensSidebar = workspace.tokensSidebar;
    await expect(
      tokensSidebar.getByRole("button", { name: "width-big" }),
    ).toBeVisible();
    await tokensSidebar.getByRole("button", { name: "width-big" }).click();

    // Check if token pill is visible on right sidebar
    const strokeSectionSidebar = rightSidebar.getByRole("region", {
      name: "stroke-section",
    });
    await expect(strokeSectionSidebar).toBeVisible();
    const firstStrokeRow = strokeSectionSidebar.getByLabel("stroke-row-0");
    await expect(firstStrokeRow).toBeVisible();
    const StrokeWidthPill = firstStrokeRow.getByRole("button", {
      name: "width-big",
    });
    await expect(StrokeWidthPill).toBeVisible();

    // Detach token from right sidebar and apply another from dropdown
    const detachButton = firstStrokeRow.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.click();
    await expect(StrokeWidthPill).not.toBeVisible();

    const tokenDropdown = firstStrokeRow.getByRole("button", {
      name: "Open token list",
    });
    await tokenDropdown.click();

    const widthOptionSmall = firstStrokeRow.getByLabel("width-small");
    await expect(widthOptionSmall).toBeVisible();
    await widthOptionSmall.click();
    const StrokeWidthPillSmall = firstStrokeRow.getByRole("button", {
      name: "width-small",
    });
    await expect(StrokeWidthPillSmall).toBeVisible();
  });

  test("User applies margin token to a shape", async ({ page }) => {
    const workspace = new WorkspacePage(page, {
      textEditor: true,
    });
    // Set up
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("workspace/get-file-layout-stroke-token-json");
    await workspace.goToWorkspace();

    // Select shape apply stroke
    await workspace.layers
      .getByTestId("layer-row")
      .nth(1)
      .getByTestId("toggle-content")
      .click();

    await workspace.layers.getByTestId("layer-row").nth(2).click();

    const rightSidebar = page.getByTestId("right-sidebar");
    await expect(rightSidebar).toBeVisible();
    await rightSidebar.getByTestId("add-stroke").click();

    // Apply margin token from token panel
    const tokensTab = page.getByRole("tab", { name: "Tokens" });
    await expect(tokensTab).toBeVisible();
    await tokensTab.click();
    await page.getByRole("button", { name: "Dimensions 4" }).click();
    await page.getByRole("button", { name: "dim", exact: true }).click();
    const tokensSidebar = workspace.tokensSidebar;
    await expect(
      tokensSidebar.getByRole("button", { name: "dim.md" }),
    ).toBeVisible();
    await tokensSidebar
      .getByRole("button", { name: "dim.md" })
      .click({ button: "right" });
    await page
      .getByTestId("tokens-context-menu-for-token")
      .getByText("Spacing")
      .hover();
    await page
      .getByTestId("tokens-context-menu-for-token")
      .getByText("Horizontal")
      .click();

    // Check if token pill is visible on right sidebar
    const layoutItemSectionSidebar = rightSidebar.getByRole("region", {
      name: "layout item menu",
    });
    await expect(layoutItemSectionSidebar).toBeVisible();
    const marginPillMd = layoutItemSectionSidebar.getByRole("button", {
      name: "dim.md",
    });
    await expect(marginPillMd).toBeVisible();

    await marginPillMd.click();
    const dimensionTokenOptionXl = page.getByRole("option", { name: "dim.xl" });
    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    const marginPillXL = layoutItemSectionSidebar.getByRole("button", {
      name: "dim.xl",
    });
    await expect(marginPillXL).toBeVisible();

    // Detach token from right sidebar and apply another from dropdown
    const detachButton = layoutItemSectionSidebar.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.click();
    await expect(marginPillXL).not.toBeVisible();
    const horizontalMarginInput = layoutItemSectionSidebar.getByText(
      "Horizontal marginOpen token",
    );
    await expect(horizontalMarginInput).toBeVisible();

    const tokenDropdown = horizontalMarginInput.getByRole("button", {
      name: "Open token list",
    });
    await tokenDropdown.click();

    await expect(dimensionTokenOptionXl).toBeVisible();
    await dimensionTokenOptionXl.click();

    await expect(marginPillXL).toBeVisible();
  });
});

test.describe("Tokens: Detach token", () => {
  test("User applies border-radius token to a shape from sidebar", async ({
    page,
  }) => {
    const { workspacePage, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(1).click();

    // Open tokens sections on left sidebar
    const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
    await tokensTabButton.click();

    // Unfold border radius tokens
    await page.getByRole("button", { name: "Border Radius 3" }).click();
    await expect(
      tokensSidebar.getByRole("button", { name: "borderRadius" }),
    ).toBeVisible();
    await tokensSidebar.getByRole("button", { name: "borderRadius" }).click();
    await expect(
      tokensSidebar.getByRole("button", { name: "borderRadius.sm" }),
    ).toBeVisible();

    // Apply border radius token from token panels
    await tokensSidebar
      .getByRole("button", { name: "borderRadius.sm" })
      .click();

    // Check if border radius sections is visible on right sidebar
    const borderRadiusSection = page.getByRole("region", {
      name: "border-radius-section",
    });
    await expect(borderRadiusSection).toBeVisible();

    // Check if token pill is visible on design tab on right sidebar
    const brTokenPillSM = borderRadiusSection.getByRole("button", {
      name: "borderRadius.sm",
    });
    await expect(brTokenPillSM).toBeVisible();
    await brTokenPillSM.click();

    // Rename token
    await tokensSidebar
      .getByRole("button", { name: "borderRadius.sm" })
      .click({ button: "right" });
    await expect(page.getByText("Edit token")).toBeVisible();
    await page.getByText("Edit token").click();
    const editModal = page.getByTestId("token-update-create-modal");
    await expect(editModal).toBeVisible();
    await expect(
      editModal.getByRole("textbox", { name: "Name" }),
    ).toBeVisible();
    await editModal
      .getByRole("textbox", { name: "Name" })
      .fill("BorderRadius.smBis");
    const submitButton = editModal.getByRole("button", { name: "Save" });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();
    await expect(page.getByText("Don't remap")).toBeVisible();
    await page.getByText("Don't remap").click();
    const brokenPill = borderRadiusSection.getByRole("button", {
      name: "This token is not in any",
    });
    await expect(brokenPill).toBeVisible();

    // Detach broken token
    const detachButton = borderRadiusSection.getByRole("button", {
      name: "Detach token",
    });
    await detachButton.click();
    await expect(brokenPill).not.toBeVisible();

    //De-select and select shape again to double check token is detached
    await page.getByRole("tab", { name: "Layers" }).click();

    await workspacePage.layers.getByTestId("layer-row").nth(0).click();
    await workspacePage.layers.getByTestId("layer-row").nth(1).click();
    await expect(brokenPill).not.toBeVisible();
  });
});
