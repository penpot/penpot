import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

const setupEmptyTokensFile = async (page) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "get-team?id=*",
    "workspace/get-team-tokens.json",
  );

  await workspacePage.goToWorkspace();

  const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
  await tokensTabButton.click();

  return {
    workspacePage,
    tokensUpdateCreateModal: workspacePage.tokensUpdateCreateModal,
    tokenThemesSetsSidebar: workspacePage.tokenThemesSetsSidebar,
    tokenSetItems: workspacePage.tokenSetItems,
    tokenSetGroupItems: workspacePage.tokenSetGroupItems,
  };
};

const setupTokensFile = async (page) => {
  const workspacePage = new WorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "get-team?id=*",
    "workspace/get-team-tokens.json",
  );
  await workspacePage.mockRPC(/get\-file\?/, "workspace/get-file-tokens.json");
  await workspacePage.mockRPC(
    /get\-file\-fragment\?/,
    "workspace/get-file-fragment-tokens.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "51e13852-1a8e-8037-8005-9e9413a1f1f6",
    pageId: "51e13852-1a8e-8037-8005-9e9413a1f1f7",
  });

  const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
  await tokensTabButton.click();

  return {
    workspacePage,
    tokensUpdateCreateModal: workspacePage.tokensUpdateCreateModal,
    tokenThemesSetsSidebar: workspacePage.tokenThemesSetsSidebar,
    tokenSetItems: workspacePage.tokenSetItems,
    tokenSetGroupItems: workspacePage.tokenSetGroupItems,
    tokensSidebar: workspacePage.tokensSidebar,
    tokenContextMenuForToken: workspacePage.tokenContextMenuForToken,
  };
};

test.describe("Tokens: Tokens Tab", () => {
  test("Clicking tokens tab button opens tokens sidebar tab", async ({
    page,
  }) => {
    const { workspacePage, tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    await expect(tokensTabPanel).toHaveText(/TOKENS/);
    await expect(tokensTabPanel).toHaveText(/Themes/);
  });

  test("User creates color token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const { workspacePage, tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel.getByTitle("Add token: Color").click();

    // Create color token with mouse

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await nameField.click();
    await nameField.fill("color.primary");

    await valueField.click();
    await valueField.fill("red");

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(tokensTabPanel.getByLabel("primary")).toBeEnabled();

    // Create token referencing the previous one with keyboard

    await tokensTabPanel.getByTitle("Add token: Color").click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    await nameField.click();
    await nameField.fill("color.secondary");
    await nameField.press("Tab");

    await valueField.click();
    await valueField.fill("{color.primary}");

    await expect(submitButton).toBeEnabled();
    await nameField.press("Enter");

    const referenceToken = tokensTabPanel.getByLabel("color.secondary");
    await expect(referenceToken).toBeEnabled();

    // Tokens tab panel should have two tokens with the color red / #ff0000
    await expect(tokensTabPanel.getByTitle("#ff0000")).toHaveCount(2);

    // Global set has been auto created and is active
    await expect(
      tokenThemesSetsSidebar.getByRole("button", {
        name: "Global",
      }),
    ).toHaveCount(1);
    await expect(
      tokenThemesSetsSidebar.getByRole("button", {
        name: "Global",
      }),
    ).toHaveAttribute("aria-checked", "true");
  });

  test("User edits token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const {
      workspacePage,
      tokensUpdateCreateModal,
      tokenThemesSetsSidebar,
      tokensSidebar,
      tokenContextMenuForToken,
    } = await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });
    await expect(tokensColorGroup).toBeVisible;
    await tokensColorGroup.click();

    const colorToken = tokensSidebar.getByRole("button", {
      name: "colors.blue.100",
    });
    await expect(colorToken).toBeVisible;
    await colorToken.click({ button: "right" });

    await expect(tokenContextMenuForToken).toBeVisible();
    await tokenContextMenuForToken.getByText("Edit token").click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    await nameField.pressSequentially(".changed");

    await nameField.press("Enter");

    await expect(tokensUpdateCreateModal).not.toBeVisible();

    const colorTokenChanged = tokensSidebar.getByRole("button", {
      name: "colors.blue.100.changed",
    });
    await expect(colorTokenChanged).toBeVisible();
  });

  test("User creates grouped color token", async ({ page }) => {
    const { workspacePage, tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel.getByTitle("Add token: Color").click();

    // Create grouped color token with mouse

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await nameField.click();
    await nameField.fill("color.dark.primary");

    await valueField.click();
    await valueField.fill("red");

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(tokensTabPanel.getByLabel("color.dark.primary")).toBeEnabled();
  });
});

test.describe("Tokens: Sets Tab", () => {
  const createSet = async (sidebar, setName, finalKey = "Enter") => {
    const tokensTabButton = sidebar
      .getByRole("button", { name: "Add set" })
      .click();

    const setInput = sidebar.locator("input:focus");
    await expect(setInput).toBeVisible();
    await setInput.fill(setName);
    await setInput.press(finalKey);
  };

  // test("User creates sets tree structure by entering a set path", async ({
  //   page,
  // }) => {
  //   const {
  //     workspacePage,
  //     tokenThemesSetsSidebar,
  //     tokenSetItems,
  //     tokenSetGroupItems,
  //   } = await setupEmptyTokensFile(page);
  //
  //   const tokensTabButton = tokenThemesSetsSidebar
  //     .getByRole("button", { name: "Add set" })
  //     .click();
  //
  //   await createSet(tokenThemesSetsSidebar, "core/colors/light");
  //   await createSet(tokenThemesSetsSidebar, "core/colors/dark");
  //
  //   // User cancels during editing
  //   await createSet(tokenThemesSetsSidebar, "core/colors/dark", "Escape");
  //
  //   await expect(tokenSetItems).toHaveCount(2);
  //   await expect(tokenSetGroupItems).toHaveCount(2);
  // });
});
