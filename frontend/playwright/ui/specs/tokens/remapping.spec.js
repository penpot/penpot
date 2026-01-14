import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import {
  setupEmptyTokensFile,
  setupTokensFile,
  setupTypographyTokensFile,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens: Remapping Feature", () => {
  test.describe("Box Shadow Token Remapping", () => {
    test("User renames box shadow token with alias references", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
      } = await setupTokensFile(page, { flags: ["enable-token-shadow"] });

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base shadow token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Shadow" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("base-shadow");

      const colorField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Color",
      });
      await colorField.fill("#000000");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived shadow token that references base-shadow
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Shadow" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Name",
      });
      await nameField.fill("derived-shadow");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Reference",
      });
      await referenceField.fill("{base-shadow}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Rename base-shadow token
      const baseToken = tokensSidebar.getByRole("button", {
        name: "base-shadow",
      });
      await baseToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("foundation-shadow");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });
      await expect(remappingModal).toContainText("1");

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "foundation-shadow" }),
      ).toBeVisible();
      await expect(
        tokensSidebar.getByRole("button", { name: "derived-shadow" }),
      ).toBeVisible();
    });

    test("User renames and updates shadow token - referenced token and applied shapes update", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
        workspacePage,
      } = await setupTokensFile(page, { flags: ["enable-token-shadow"] });

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base shadow token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Shadow" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("primary-shadow");

      let colorField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Color",
      });
      await colorField.fill("#000000");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived shadow token that references base
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Shadow" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("card-shadow");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Reference",
      });
      await referenceField.fill("{primary-shadow}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Apply the referenced token to a shape
      await page.getByRole("tab", { name: "Layers" }).click();
      await workspacePage.layers
        .getByTestId("layer-row")
        .filter({ hasText: "Button" })
        .click();

      await page.getByRole("tab", { name: "Tokens" }).click();
      const cardShadowToken = tokensSidebar.getByRole("button", {
        name: "card-shadow",
      });
      await cardShadowToken.click();

      // Rename and update value of base token
      const primaryToken = tokensSidebar.getByRole("button", {
        name: "primary-shadow",
      });
      await primaryToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("main-shadow");

      // Update the color value
      colorField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Color",
      });
      await colorField.fill("#FF0000");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Confirm remapping
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify base token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "main-shadow" }),
      ).toBeVisible();

      // Verify referenced token still exists
      await expect(
        tokensSidebar.getByRole("button", { name: "card-shadow" }),
      ).toBeVisible();

      // Verify the shape still has the token applied with the NEW name
      await page.getByRole("tab", { name: "Layers" }).click();
      await workspacePage.layers
        .getByTestId("layer-row")
        .filter({ hasText: "Button" })
        .click();

      // Verify the shape still has the shadow applied with the UPDATED color value
      // Expand the shadow section to access the color field
      const shadowSection =
        workspacePage.rightSidebar.getByTestId("shadow-section");
      await expect(shadowSection).toBeVisible();

      // Click to expand the shadow options (the menu button)
      const shadowMenuButton = shadowSection
        .getByRole("button", { name: "options" })
        .first();
      await shadowMenuButton.click();

      // Wait for the advanced options to appear
      await page.waitForTimeout(500);

      // Verify the color value has updated from #000000 to #FF0000
      const colorInput = shadowSection.getByRole("textbox", { name: "Color" });
      expect(colorInput).not.toBeNull();
      const colorValue = await colorInput.inputValue();
      expect(colorValue.toUpperCase()).toBe("FF0000");
    });
  });

  test.describe("Typography Token Remapping", () => {
    test("User renames typography token with alias references", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
      } = await setupTypographyTokensFile(page);

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base typography token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Typography" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("base-text");

      const fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Font size",
      });
      await fontSizeField.fill("16");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived typography token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Typography" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Name",
      });
      await nameField.fill("body-text");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Reference",
      });
      await referenceField.fill("{base-text}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Rename base token
      const baseToken = tokensSidebar.getByRole("button", {
        name: "base-text",
      });
      await baseToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("default-text");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "default-text" }),
      ).toBeVisible();
      await expect(
        tokensSidebar.getByRole("button", { name: "body-text" }),
      ).toBeVisible();
    });

    test("User renames and updates typography token - referenced token and applied shapes update", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
        workspacePage,
      } = await setupTypographyTokensFile(page);

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base typography token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Typography" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("body-style");

      let fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Font size",
      });
      await fontSizeField.fill("16");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived typography token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Typography" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Name",
      });
      await nameField.fill("paragraph-style");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Reference",
      });
      await referenceField.fill("{body-style}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Apply the referenced token to a text shape
      await page.getByRole("tab", { name: "Layers" }).click();
      await workspacePage.layers
        .getByTestId("layer-row")
        .filter({ hasText: "Some Text" })
        .click();

      await page.getByRole("tab", { name: "Tokens" }).click();
      const paragraphToken = tokensSidebar.getByRole("button", {
        name: "paragraph-style",
      });
      await paragraphToken.click();

      // Rename and update value of base token
      const bodyToken = tokensSidebar.getByRole("button", {
        name: "body-style",
      });
      await bodyToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("text-base");

      // Update the font size value
      fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Font size",
      });
      await fontSizeField.fill("18");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Confirm remapping
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify base token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "text-base" }),
      ).toBeVisible();

      // Verify referenced token still exists
      await expect(
        tokensSidebar.getByRole("button", { name: "paragraph-style" }),
      ).toBeVisible();

      // Verify the text shape still has the token applied with NEW name and value
      await page.getByRole("tab", { name: "Layers" }).click();
      await workspacePage.layers
        .getByTestId("layer-row")
        .filter({ hasText: "Some Text" })
        .click();

      // Verify the shape shows the updated font size value (18)
      // This proves the remapping worked and the value update propagated through the reference
      const fontSizeInput = workspacePage.rightSidebar.getByRole("textbox", {
        name: "Font Size",
      });
      await expect(fontSizeInput).toBeVisible();
      await expect(fontSizeInput).toHaveValue("18");
    });
  });

  test.describe("Border Radius Token Remapping", () => {
    test("User renames border radius token with alias references", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
      } = await setupTokensFile(page);

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base border radius token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Border Radius" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("base-radius");

      const valueField = tokensUpdateCreateModal.getByLabel("Value");
      await valueField.fill("4");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived border radius token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Border Radius" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("card-radius");

      const valueField2 = tokensUpdateCreateModal.getByLabel("Value");
      await valueField2.fill("{base-radius}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Rename base token
      const baseToken = tokensSidebar.getByRole("button", {
        name: "base-radius",
      });
      await baseToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("primary-radius");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "primary-radius" }),
      ).toBeVisible();
      await expect(
        tokensSidebar.getByRole("button", { name: "card-radius" }),
      ).toBeVisible();
    });

    test("User renames and updates border radius token - referenced token updates", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
      } = await setupTokensFile(page);

      const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

      // Create base border radius token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Border Radius" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("radius-sm");

      let valueField = tokensUpdateCreateModal.getByLabel("Value");
      await valueField.fill("4");

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Create derived border radius token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Border Radius" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("button-radius");

      const valueField2 = tokensUpdateCreateModal.getByLabel("Value");
      await valueField2.fill("{radius-sm}");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();
      await expect(tokensUpdateCreateModal).not.toBeVisible();

      // Rename and update value of base token
      const radiusToken = tokensSidebar.getByRole("button", {
        name: "radius-sm",
      });
      await radiusToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("radius-base");

      // Update the value
      valueField = tokensUpdateCreateModal.getByLabel("Value");
      await valueField.fill("8");

      submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Confirm remapping
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: /remap/i,
      });
      await confirmButton.click();

      // Verify base token was renamed
      await expect(
        tokensSidebar.getByRole("button", { name: "radius-base" }),
      ).toBeVisible();

      // Verify referenced token still exists
      await expect(
        tokensSidebar.getByRole("button", { name: "button-radius" }),
      ).toBeVisible();

      // Verify the referenced token now points to the renamed token
      // by opening it and checking the reference
      const buttonRadiusToken = tokensSidebar.getByRole("button", {
        name: "button-radius",
      });
      await buttonRadiusToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      const currentValue = tokensUpdateCreateModal.getByLabel("Value");
      await expect(currentValue).toHaveValue("{radius-base}");
    });
  });
});
