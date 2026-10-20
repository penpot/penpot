import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import {
  setupEmptyTokensFileRender,
  setupTypographyTokensFileRender,
  unfoldTokenType,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

const openEditModal = async (page, tokensTabPanel, name) => {
  await tokensTabPanel
    .getByRole("button", { name, exact: true })
    .click({ button: "right" });
  await page.getByText("Edit token").click();
};

// Hovers the reference input and clicks the detach button, which is only
// visible while the reference row is hovered or focused.
const detachReference = async (modal) => {
  const referenceField = modal.getByRole("textbox", { name: "Reference" });
  const detachButton = modal.getByRole("button", { name: "Detach reference" });

  await expect(referenceField).toBeVisible();
  await expect(detachButton).toBeHidden();

  await referenceField.hover();
  await expect(detachButton).toBeVisible();
  await detachButton.click();
};

test.describe("Tokens - detach reference", () => {
  test("User detaches a typography reference and edits the copied values", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokensSidebar } =
      await setupTypographyTokensFileRender(page);
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    const saveButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // Create a typography token that references "Full" from the fixture.
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Typography" })
      .click();
    await expect(tokensUpdateCreateModal).toBeVisible();
    await tokensUpdateCreateModal.getByLabel("Name").fill("detached");
    await tokensUpdateCreateModal.getByTestId("reference-opt").click();
    await tokensUpdateCreateModal
      .getByRole("textbox", { name: "Reference" })
      .fill("{Full}");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    await expect(tokensUpdateCreateModal).not.toBeVisible();

    await unfoldTokenType(tokensSidebar, "typography");
    await openEditModal(page, tokensTabPanel, "detached");
    await expect(tokensUpdateCreateModal).toBeVisible();

    await detachReference(tokensUpdateCreateModal);

    // The values of "Full" are now in the composite tab.
    const fontFamilyField = tokensUpdateCreateModal
      .getByRole("textbox", { name: "Font family" })
      .first();
    const fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Font Size",
    });

    await expect(fontFamilyField).toHaveValue("42dot Sans");
    await expect(fontSizeField).toHaveValue("100");
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Font Weight" }),
    ).toHaveValue("300");
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Line Height" }),
    ).toHaveValue("2");
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Letter Spacing" }),
    ).toHaveValue("2");
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Text Case" }),
    ).toHaveValue("uppercase");
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Text Decoration" }),
    ).toHaveValue("underline");

    await fontSizeField.fill("24");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    await expect(tokensUpdateCreateModal).not.toBeVisible();

    // The detached token keeps its own values.
    await openEditModal(page, tokensTabPanel, "detached");
    await expect(fontSizeField).toHaveValue("24");
    await tokensUpdateCreateModal
      .getByRole("button", { name: "Cancel" })
      .click();

    // The referenced token is not modified.
    await openEditModal(page, tokensTabPanel, "Full");
    await expect(fontSizeField).toHaveValue("100");
  });

  test("User detaches a shadow reference and edits the copied values", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal } = await setupEmptyTokensFileRender(page, {
      flags: ["enable-token-shadow"],
    });
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    const addShadowButton = tokensTabPanel.getByRole("button", {
      name: "Add Token: Shadow",
    });
    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const colorField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Color",
    });
    const offsetXField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "X",
    });
    const offsetYField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Y",
    });
    const blurField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Blur",
    });
    const spreadField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Spread",
    });
    const saveButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // Base shadow token.
    await addShadowButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();
    await nameField.fill("base-shadow");
    await colorField.fill("red");
    await offsetXField.fill("2");
    await offsetYField.fill("3");
    await blurField.fill("5");
    await spreadField.fill("1");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    await expect(tokensUpdateCreateModal).not.toBeVisible();

    // Shadow token that references the base one.
    await addShadowButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();
    await nameField.fill("alias-shadow");
    await tokensUpdateCreateModal.getByTestId("reference-opt").click();
    await tokensUpdateCreateModal
      .getByRole("textbox", { name: "Reference" })
      .fill("{base-shadow}");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    await expect(tokensUpdateCreateModal).not.toBeVisible();

    await unfoldTokenType(tokensTabPanel, "shadow");
    await openEditModal(page, tokensTabPanel, "alias-shadow");
    await expect(tokensUpdateCreateModal).toBeVisible();

    await detachReference(tokensUpdateCreateModal);

    // The values of "base-shadow" are now in the shadow tab.
    await expect(colorField).toHaveValue("red");
    await expect(offsetXField).toHaveValue("2");
    await expect(offsetYField).toHaveValue("3");
    await expect(blurField).toHaveValue("5");
    await expect(spreadField).toHaveValue("1");

    await offsetXField.fill("10");
    await expect(saveButton).toBeEnabled();
    await saveButton.click();
    await expect(tokensUpdateCreateModal).not.toBeVisible();

    // The detached token keeps its own values.
    await openEditModal(page, tokensTabPanel, "alias-shadow");
    await expect(offsetXField).toHaveValue("10");
    await tokensUpdateCreateModal
      .getByRole("button", { name: "Cancel" })
      .click();

    // The referenced token is not modified.
    await openEditModal(page, tokensTabPanel, "base-shadow");
    await expect(offsetXField).toHaveValue("2");
  });
});
