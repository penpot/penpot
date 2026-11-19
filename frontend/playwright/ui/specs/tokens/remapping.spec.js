import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { createToken, setupTokensFileRender } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-design-tokens-v1",
  ]);
  await WasmWorkspacePage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

const createTokenCombobox = async (page, type, name, textFieldName, value) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  const { tokensUpdateCreateModal } = await setupTokensFileRender(page, {
    flags: ["enable-token-shadow"],
  });

  // Create base token
  await tokensTabPanel
    .getByRole("button", { name: `Add Token: ${type}` })
    .click();
  await expect(tokensUpdateCreateModal).toBeVisible();

  const nameField = tokensUpdateCreateModal.getByLabel("Name");
  await nameField.fill(name);

  const valueFill = tokensUpdateCreateModal.getByRole("combobox", {
    name: textFieldName,
  });
  await valueFill.fill(value);

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await submitButton.click();
  await expect(tokensUpdateCreateModal).not.toBeVisible();
};

const renameToken = async (page, oldName, newName) => {
  const { tokensUpdateCreateModal, tokensSidebar, tokenContextMenuForToken } =
    await setupTokensFileRender(page, { flags: ["enable-token-shadow"] });

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

  const { tokensUpdateCreateModal } = await setupTokensFileRender(page, {
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

test.describe("Remapping a single token", () => {
  test.describe("Box Shadow Token Remapping", () => {
    test("User renames box shadow token with alias references", async ({
      page,
    }) => {
      const { tokensSidebar } = await setupTokensFileRender(page, {
        flags: ["enable-token-shadow"],
      });

      // Create base shadow token
      await createToken(
        page,
        "Shadow",
        "base-shadow",
        "Color",
        "textbox",
        "#000000",
      );

      // Create derived shadow token that references base-shadow
      await createCompositeDerivedToken(
        page,
        "Shadow",
        "derived-shadow",
        "{base-shadow}",
      );

      const remappingModal = page.getByTestId("token-remapping-modal");

      await test.step("Remap the references", async () => {
        await renameToken(page, "base-shadow", "foundation-shadow");

        await expect(remappingModal).toBeVisible({ timeout: 5000 });
        await expect(remappingModal).toContainText("base-shadow");
        await expect(remappingModal).toContainText("foundation-shadow");

        await remappingModal
          .getByRole("button", { name: "remap tokens" })
          .click();

        await expect(
          tokensSidebar.getByRole("button", { name: "foundation-shadow" }),
        ).toBeVisible();
        await expect(
          tokensSidebar.getByRole("button", { name: "derived-shadow" }),
        ).toBeVisible();
      });

      await test.step("Close the modal and keep the original name", async () => {
        await renameToken(page, "foundation-shadow", "shadow-base");

        await expect(remappingModal).toBeVisible({ timeout: 5000 });
        await remappingModal.getByRole("button", { name: "close" }).click();

        await expect(
          tokensSidebar.getByRole("button", { name: "foundation-shadow" }),
        ).toBeVisible();
        await expect(
          tokensSidebar.getByRole("button", { name: "derived-shadow" }),
        ).toBeVisible();
      });

      await test.step("Do not remap and break the reference", async () => {
        await renameToken(page, "foundation-shadow", "shadow-base");

        await expect(remappingModal).toBeVisible({ timeout: 5000 });
        await remappingModal
          .getByRole("button", { name: "don't remap" })
          .click();

        await expect(
          tokensSidebar.getByRole("button", { name: "shadow-base" }),
        ).toBeVisible();
        await expect(
          tokensSidebar.locator('[aria-label="Missing reference"]'),
        ).toBeVisible();
      });
    });
  });

  test.describe("Border Radius Token Remapping", () => {
    test("User renames and updates border radius token - referenced token updates", async ({
      page,
    }) => {
      const {
        tokensUpdateCreateModal,
        tokensSidebar,
        tokenContextMenuForToken,
      } = await setupTokensFileRender(page, {
        flags: ["enable-token-combobox", "enable-feature-token-input"],
      });

      // Create base border radius token
      await createTokenCombobox(
        page,
        "Border Radius",
        "radius-sm",
        "Value",
        "4",
      );

      // Create derived border radius token
      await createTokenCombobox(
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
});

test.describe("Remapping group of tokens", () => {
  test("User renames a group, remaps and undoes - tokens remain applied", async ({
    page,
  }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);
    const workspacePage = new WasmWorkspacePage(page);
    const rightSidebar = workspacePage.rightSidebar;

    // Create multiple tokens in a group. Use a name not present in the mock
    // file to avoid conflicts with pre-existing token groups.
    await createToken(
      page,
      "Color",
      "brand.primary",
      "Value",
      "textbox",
      "#0000FF",
    );
    await createToken(
      page,
      "Color",
      "brand.secondary",
      "Value",
      "textbox",
      "#0055FF",
    );

    const brandNode = tokensSidebar.getByRole("button", {
      name: "brand",
      exact: true,
    });

    await expect(brandNode).toBeVisible();

    // Apply the token to a shape so that references exist and the
    // remapping modal is triggered on rename
    await page.getByRole("tab", { name: "Layers" }).click();
    await page
      .getByTestId("layer-row")
      .filter({ hasText: "Rectangle" })
      .first()
      .click();

    await page.getByRole("tab", { name: "Tokens" }).click();
    const brandPrimaryToken = tokensSidebar.getByRole("button", {
      name: "primary",
    });
    await brandPrimaryToken.click();

    // Rename the group
    await brandNode.click({ button: "right" });
    const renameNodeButton = page.getByRole("button", {
      name: "Rename",
      exact: true,
    });
    await expect(renameNodeButton).toBeVisible();
    await renameNodeButton.click();

    const tokenRenameNodeModal = page.getByTestId("token-rename-node-modal");
    await expect(tokenRenameNodeModal).toBeVisible();

    const nameField = tokenRenameNodeModal.getByRole("textbox", {
      name: "Name",
    });
    await nameField.fill("brandy");

    const submitButton = tokenRenameNodeModal.getByRole("button", {
      name: "Rename",
    });
    await submitButton.click();

    // Confirm remapping
    const remappingModal = page.getByTestId("token-remapping-modal");
    await expect(remappingModal).toBeVisible({ timeout: 5000 });

    const confirmButton = remappingModal.getByRole("button", {
      name: "remap tokens",
    });
    await confirmButton.click();

    // Verify the group was renamed and the token is still applied
    const brandyNode = tokensSidebar.getByRole("button", {
      name: "brandy",
      exact: true,
    });
    await expect(brandyNode).toBeVisible();

    const fillSection = rightSidebar.getByRole("region", {
      name: "Fill section",
    });
    await expect(fillSection).toBeVisible();
    await expect(
      fillSection.getByLabel("brandy.primary", { exact: true }),
    ).toBeVisible();

    // Undo the rename and remap as a single operation
    await page.keyboard.press("ControlOrMeta+z");

    // The original group name and token application should be fully restored
    await expect(brandNode).toBeVisible();
    await expect(
      fillSection.getByLabel("brand.primary", { exact: true }),
    ).toBeVisible();
  });
});
