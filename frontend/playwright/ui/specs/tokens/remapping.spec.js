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

const createToken = async (page, type, name, textFieldName, value) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  const { tokensUpdateCreateModal } = await setupTokensFile(page, {
    flags: ["enable-token-shadow"],
  });

  // Create base token
  await tokensTabPanel
    .getByRole("button", { name: `Add Token: ${type}` })
    .click();
  await expect(tokensUpdateCreateModal).toBeVisible();

  const nameField = tokensUpdateCreateModal.getByLabel("Name");
  await nameField.fill(name);

  const colorField = tokensUpdateCreateModal.getByRole("textbox", {
    name: textFieldName,
  });
  await colorField.fill(value);

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await submitButton.click();
  await expect(tokensUpdateCreateModal).not.toBeVisible();
};

const renameToken = async (page, oldName, newName) => {
  const { tokensUpdateCreateModal, tokensSidebar, tokenContextMenuForToken } =
    await setupTokensFile(page, { flags: ["enable-token-shadow"] });

  const baseToken = tokensSidebar.getByRole("button", {
    name: oldName,
  });
  await baseToken.click({ button: "right" });
  await tokenContextMenuForToken.getByText("Edit token").click();

  await expect(tokensUpdateCreateModal).toBeVisible();

  const nameField = tokensUpdateCreateModal.getByLabel("Name");
  await nameField.fill(newName);

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await submitButton.click();
};

const createCompositeDerivedToken = async (page, type, name, reference) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  const { tokensUpdateCreateModal } = await setupTokensFile(page, {
    flags: ["enable-token-shadow"],
  });

  await tokensTabPanel
    .getByRole("button", { name: `Add Token: ${type}` })
    .click();
  await expect(tokensUpdateCreateModal).toBeVisible();

  const nameField = tokensUpdateCreateModal.getByRole("textbox", {
    name: "Name",
  });
  await nameField.fill(name);

  const referenceToggle = tokensUpdateCreateModal.getByTestId("reference-opt");
  await referenceToggle.click();

  const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
    name: "Reference",
  });
  await referenceField.fill(reference);

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await submitButton.click();
  await expect(tokensUpdateCreateModal).not.toBeVisible();
};

test.describe("Remapping Tokens", () => {
  test.describe("Box Shadow Token Remapping", () => {
    test("User renames box shadow token with alias references", async ({
      page,
    }) => {
      const { tokensSidebar } = await setupTokensFile(page, {
        flags: ["enable-token-shadow"],
      });

      // Create base shadow token
      await createToken(page, "Shadow", "base-shadow", "Color", "#000000");

      // Create derived shadow token that references base-shadow
      await createCompositeDerivedToken(
        page,
        "Shadow",
        "derived-shadow",
        "{base-shadow}",
      );

      // Rename base-shadow token
      await renameToken(page, "base-shadow", "foundation-shadow");

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });
      await expect(remappingModal).toContainText("base-shadow");
      await expect(remappingModal).toContainText("foundation-shadow");

      const confirmButton = remappingModal.getByRole("button", {
        name: "remap tokens",
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

      // Create base shadow token
      await createToken(page, "Shadow", "primary-shadow", "Color", "#000000");

      // Create derived shadow token that references base
      await createCompositeDerivedToken(
        page,
        "Shadow",
        "card-shadow",
        "{primary-shadow}",
      );

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
      const nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("main-shadow");

      // Update the color value
      const colorField = tokensUpdateCreateModal.getByRole("textbox", {
        name: "Color",
      });
      await colorField.fill("#FF0000");

      const submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Confirm remapping
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: "remap tokens",
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
      await createToken(page, "Typography", "base-text", "Font size", "16");

      // Create derived typography token
      await createCompositeDerivedToken(
        page,
        "Typography",
        "body-text",
        "{base-text}",
      );

      // Rename base token
      await renameToken(page, "base-text", "default-text");

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: "remap tokens",
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
      await createToken(page, "Typography", "body-style", "Font size", "16");

      // Create derived typography token
      await tokensTabPanel
        .getByRole("button", { name: "Add Token: Typography" })
        .click();
      await expect(tokensUpdateCreateModal).toBeVisible();

      let nameField = tokensUpdateCreateModal.getByRole("textbox", {
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

      let submitButton = tokensUpdateCreateModal.getByRole("button", {
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
      const fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
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
        name: "remap tokens",
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
      const { tokensSidebar } = await setupTokensFile(page);

      // Create base border radius token
      await createToken(page, "Border Radius", "base-radius", "Value", "4");

      // Create derived border radius token
      await createToken(
        page,
        "Border Radius",
        "card-radius",
        "Value",
        "{base-radius}",
      );

      // Rename base token
      await renameToken(page, "base-radius", "primary-radius");

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: "remap tokens",
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

      // Create base border radius token
      await createToken(page, "Border Radius", "radius-sm", "Value", "4");

      // Create derived border radius token
      await createToken(
        page,
        "Border Radius",
        "button-radius",
        "Value",
        "{radius-sm}",
      );

      // Rename and update value of base token
      const radiusToken = tokensSidebar.getByRole("button", {
        name: "radius-sm",
      });
      await radiusToken.click({ button: "right" });
      await tokenContextMenuForToken.getByText("Edit token").click();

      await expect(tokensUpdateCreateModal).toBeVisible();
      const nameField = tokensUpdateCreateModal.getByLabel("Name");
      await nameField.fill("radius-base");

      // Update the value
      const valueField = tokensUpdateCreateModal.getByLabel("Value");
      await valueField.fill("8");

      const submitButton = tokensUpdateCreateModal.getByRole("button", {
        name: "Save",
      });
      await submitButton.click();

      // Confirm remapping
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const confirmButton = remappingModal.getByRole("button", {
        name: "remap tokens",
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

  test.describe("Cancel remap", () => {
    test("Only rename - breaks reference", async ({ page }) => {
      const { tokensSidebar } = await setupTokensFile(page, {
        flags: ["enable-token-shadow"],
      });

      // Create base shadow token
      await createToken(page, "Shadow", "base-shadow", "Color", "#000000");

      // Create derived shadow token that references base-shadow
      await createCompositeDerivedToken(
        page,
        "Shadow",
        "derived-shadow",
        "{base-shadow}",
      );

      // Rename base-shadow token
      await renameToken(page, "base-shadow", "foundation-shadow");

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const cancelButton = remappingModal.getByRole("button", {
        name: "don't remap",
      });
      await cancelButton.click();

      // Verify token was renamed
      await expect(
        tokensSidebar.getByRole("button", {
          name: "foundation-shadow",
        }),
      ).toBeVisible();
      await expect(
        tokensSidebar.locator('[aria-label="Missing reference"]'),
      ).toBeVisible();
    });

    test("Cancel process - no changes applied", async ({ page }) => {
      const { tokensSidebar } = await setupTokensFile(page, {
        flags: ["enable-token-shadow"],
      });

      // Create base shadow token
      await createToken(page, "Shadow", "base-shadow", "Color", "#000000");

      // Create derived shadow token that references base-shadow
      await createCompositeDerivedToken(
        page,
        "Shadow",
        "derived-shadow",
        "{base-shadow}",
      );

      // Rename base-shadow token
      await renameToken(page, "base-shadow", "foundation-shadow");

      // Check for remapping modal
      const remappingModal = page.getByTestId("token-remapping-modal");
      await expect(remappingModal).toBeVisible({ timeout: 5000 });

      const closeButton = remappingModal.getByRole("button", {
        name: "close",
      });
      await closeButton.click();

      // Verify original token name still exists
      await expect(
        tokensSidebar.getByRole("button", { name: "base-shadow" }),
      ).toBeVisible();
      await expect(
        tokensSidebar.getByRole("button", { name: "derived-shadow" }),
      ).toBeVisible();
    });
  });
});
