import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import { createToken, setupTokensFileRender, unfoldTokenType } from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens - node tree", () => {
  test("User fold/unfold color tokens", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });
    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    const colorToken = tokensSidebar.getByRole("button", {
      name: "colors.blue.100",
    });
    await expect(colorToken).toBeVisible();
    await tokensColorGroup.click();
    await expect(colorToken).not.toBeVisible();
  });

  test("User renames a group", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);

    // Create multiple tokens in a group
    await createToken(page, "Color", "dark.primary", "Value", "#000000");
    await createToken(page, "Color", "dark.secondary", "Value", "#111111");

    // Verify that the node and child token are visible before deletion
    const darkNode = tokensSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });
    const darkNodeToken = tokensSidebar.getByRole("button", {
      name: "primary",
    });

    // Select a node and right click on it to open context menu
    await expect(darkNode).toBeVisible();
    await expect(darkNodeToken).toBeVisible();
    await darkNode.click({ button: "right" });

    // select "Rename" from the context menu
    const renameNodeButton = page.getByRole("button", {
      name: "Rename",
      exact: true,
    });
    await expect(renameNodeButton).toBeVisible();
    await renameNodeButton.click();

    // Expect the rename modal to be visible, fill in the new name and submit
    const tokenRenameNodeModal = page.getByTestId("token-rename-node-modal");
    await expect(tokenRenameNodeModal).toBeVisible();

    const nameField = tokenRenameNodeModal.getByRole("textbox", {
      name: "Name",
    });
    await nameField.fill("darker");

    const submitButton = tokenRenameNodeModal.getByRole("button", {
      name: "Rename",
    });
    await submitButton.click();

    // Ensure that the remapping modal does not appear
    const remappingModal = page.getByTestId("token-remapping-modal");
    await expect(remappingModal).not.toBeVisible();

    // Verify that the node has been renamed and tokens are still visible
    const darkerNode = tokensSidebar.getByRole("button", {
      name: "darker",
      exact: true,
    });

    await expect(darkerNode).toBeVisible();
  });

  test("User duplicates a group", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);

    // Create multiple tokens in a group
    await createToken(page, "Color", "dark.primary", "Value", "#000000");
    await createToken(page, "Color", "dark.secondary", "Value", "#111111");

    // Verify that the node and child token are visible before deletion
    const darkNode = tokensSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });
    const darkNodeToken = tokensSidebar.getByRole("button", {
      name: "primary",
    });

    // Select a node and right click on it to open context menu
    await expect(darkNode).toBeVisible();
    await expect(darkNodeToken).toBeVisible();
    await darkNode.click({ button: "right" });

    // select "Duplicate" from the context menu
    const duplicateNodeButton = page.getByRole("button", {
      name: "Duplicate",
      exact: true,
    });
    await expect(duplicateNodeButton).toBeVisible();
    await duplicateNodeButton.click();

    // Expect the duplicate modal to be visible, fill in the new name and submit
    const tokenDuplicateNodeModal = page.getByTestId("token-rename-node-modal");
    await expect(tokenDuplicateNodeModal).toBeVisible();

    const nameField = tokenDuplicateNodeModal.getByRole("textbox", {
      name: "Name",
    });
    await nameField.fill("darker");

    const submitButton = tokenDuplicateNodeModal.getByRole("button", {
      name: "Duplicate",
    });
    await submitButton.click();

    // Verify that the node has been duplicated and tokens are visible
    const darkerNode = tokensSidebar.getByRole("button", {
      name: "darker",
      exact: true,
    });

    const darkerNodeToken = tokensSidebar.getByRole("button", {
      name: "darker.primary",
    });

    await expect(darkerNode).toBeVisible();
    await expect(darkerNodeToken).toBeVisible();
  });

  test("User removes node and all child tokens", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFileRender(page);

    await expect(tokensSidebar).toBeVisible();

    // Expand color tokens
    await unfoldTokenType(tokensSidebar, "color");

    // Verify that the node and child token are visible before deletion
    const colorNode = tokensSidebar.getByRole("button", {
      name: "colors",
      exact: true,
    });
    const colorNodeToken = tokensSidebar.getByRole("button", {
      name: "colors.blue.100",
    });

    // Select a node and right click on it to open context menu
    await expect(colorNode).toBeVisible();
    await expect(colorNodeToken).toBeVisible();
    await colorNode.click({ button: "right" });

    // select "Delete" from the context menu
    const deleteNodeButton = page.getByRole("button", {
      name: "Delete",
      exact: true,
    });
    await expect(deleteNodeButton).toBeVisible();
    await deleteNodeButton.click();

    // Verify that the node is removed
    await expect(colorNode).not.toBeVisible();
    // Verify that child token is also removed
    await expect(colorNodeToken).not.toBeVisible();

    // Save the type button to verify that expands/folds
    const tokenTypeButton = await tokensSidebar.getByRole("button", {
      name: "Color",
      exact: true,
    });

    await expect(tokenTypeButton).toHaveAttribute("aria-expanded", "false");
  });
});
