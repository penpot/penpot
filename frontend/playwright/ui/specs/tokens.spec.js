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

  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
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
    tokenContextMenuForSet: workspacePage.tokenContextMenuForSet,
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
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
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
    tokenThemeUpdateCreateModal: workspacePage.tokenThemeUpdateCreateModal,
    tokenThemesSetsSidebar: workspacePage.tokenThemesSetsSidebar,
    tokenSetItems: workspacePage.tokenSetItems,
    tokenSetGroupItems: workspacePage.tokenSetGroupItems,
    tokensSidebar: workspacePage.tokensSidebar,
    tokenContextMenuForToken: workspacePage.tokenContextMenuForToken,
    tokenContextMenuForSet: workspacePage.tokenContextMenuForSet,
  };
};

test.describe("Tokens: Tokens Tab", () => {
  test("Clicking tokens tab button opens tokens sidebar tab", async ({
    page,
  }) => {
    await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    await expect(tokensTabPanel).toHaveText(/TOKENS/);
    await expect(tokensTabPanel).toHaveText(/Themes/);
  });

  test("User creates color token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel.getByTitle("Add token: Color").click();

    // Create color token with mouse

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await nameField.click();
    await nameField.fill("color.primary");

    // try invalid value
    await valueField.click();

    await valueField.fill("1");
    await expect(
      tokensUpdateCreateModal.getByText("Invalid color value: 1"),
    ).toBeVisible();

    // valid value
    await valueField.fill("red");

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", {
        name: "color.primary",
      }),
    ).toBeEnabled();

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

    await expect(
      tokensTabPanel.getByRole("button", {
        name: "color.secondary",
      }),
    ).toBeEnabled();

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

  test("User creates dimensions token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel.getByTitle("Add token: Dimensions").click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await nameField.click();
    await nameField.fill("dimension.spacing.small");

    // try invalid value first
    await valueField.click();

    await valueField.fill("red");
    await expect(
      tokensUpdateCreateModal.getByText("Invalid token value: red"),
    ).toBeVisible();

    // valid value
    await valueField.fill("4px");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 4"),
    ).toBeVisible();

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(
      tokensTabPanel.getByText("dimension.spacing.small"),
    ).toBeVisible();

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
    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    const colorToken = tokensSidebar.getByRole("button", {
      name: "colors.blue.100",
    });
    await expect(colorToken).toBeVisible();
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

  test("User edits theme and activates it in the sidebar", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenThemeUpdateCreateModal } =
      await setupTokensFile(page);

    await expect(tokenThemesSetsSidebar).toBeVisible();

    await tokenThemesSetsSidebar.getByRole("button", { name: "Edit" }).click();

    await expect(tokenThemeUpdateCreateModal).toBeVisible();
    await tokenThemeUpdateCreateModal
      .getByRole("button", { name: "sets" })
      .first()
      .click();

    await tokenThemeUpdateCreateModal.getByLabel("Theme").fill("Changed");

    const lightDarkSetGroup = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "LightDark",
      exact: true,
    });
    await expect(lightDarkSetGroup).toBeVisible();
    const lightSet = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "light",
      exact: true,
    });
    const darkSet = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "dark",
      exact: true,
    });

    // Mixed set group
    await expect(lightSet.getByRole("checkbox")).toBeChecked();
    await expect(darkSet.getByRole("checkbox")).not.toBeChecked();

    // Disable all
    await lightDarkSetGroup.getByRole("checkbox").click();
    await expect(lightSet.getByRole("checkbox")).not.toBeChecked();
    await expect(darkSet.getByRole("checkbox")).not.toBeChecked();

    // Enable all
    await lightDarkSetGroup.getByRole("checkbox").click();
    await expect(lightSet.getByRole("checkbox")).toBeChecked();
    await expect(darkSet.getByRole("checkbox")).toBeChecked();

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "save theme",
      })
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed" + "4 active sets"),
    ).toBeVisible();

    await tokenThemeUpdateCreateModal
      .getByRole("button")
      .getByText("close")
      .click();
    await expect(tokenThemeUpdateCreateModal).not.toBeVisible();

    const themeSelect = tokenThemesSetsSidebar.getByRole("combobox");

    await themeSelect.click();
    await page
      .getByTestId("theme-select-dropdown")
      .getByRole("option", { name: "Changed" })
      .click();

    const sidebarLightSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "light",
      exact: true,
    });
    const sidebarDarkSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });

    await expect(sidebarLightSet.getByRole("checkbox")).toBeChecked();
    await expect(sidebarDarkSet.getByRole("checkbox")).toBeChecked();
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

  test("User duplicate color token", async ({ page }) => {
    const { tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });

    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    const colorToken = tokensSidebar.getByRole("button", {
      name: "colors.blue.100",
    });

    await colorToken.click({ button: "right" });
    await expect(tokenContextMenuForToken).toBeVisible();

    await tokenContextMenuForToken.getByText("Duplicate token").click();
    await expect(tokenContextMenuForToken).not.toBeVisible();

    await expect(
      tokensSidebar.getByRole("button", { name: "colors.blue.100-copy" }),
    ).toBeVisible();
  });

  test("User delete color token", async ({ page }) => {
    const { tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

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
    await colorToken.click({ button: "right" });

    await expect(tokenContextMenuForToken).toBeVisible();
    await tokenContextMenuForToken.getByText("Delete token").click();

    await expect(tokenContextMenuForToken).not.toBeVisible();
    await expect(colorToken).not.toBeVisible();
  });

  test("User fold/unfold color tokens", async ({ page }) => {
    const { tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

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
});

test.describe("Tokens: Sets Tab", () => {
  const changeSetInput = async (sidebar, setName, finalKey = "Enter") => {
    const setInput = sidebar.locator("input:focus");
    await expect(setInput).toBeVisible();
    await setInput.fill(setName);
    await setInput.press(finalKey);
  };

  const createSet = async (sidebar, setName, finalKey = "Enter") => {
    const tokensTabButton = sidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await changeSetInput(sidebar, setName, (finalKey = "Enter"));
  };

  const assertSetsList = async (el, sets) => {
    const buttons = await el.getByRole("button").allTextContents();
    const filteredButtons = buttons.filter(
      (text) => text && text !== "Create one.",
    );
    await expect(filteredButtons).toEqual(sets);
  };

  test("User creates sets tree structure by entering a set path", async ({
    page,
  }) => {
    const { tokenThemesSetsSidebar, tokenContextMenuForSet } =
      await setupEmptyTokensFile(page);

    const tokensTabButton = tokenThemesSetsSidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await createSet(tokenThemesSetsSidebar, "core/colors/light");
    await createSet(tokenThemesSetsSidebar, "core/colors/dark");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "light",
      "dark",
    ]);

    // User renames set
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "light" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Rename").click();
    await changeSetInput(tokenThemesSetsSidebar, "light-renamed");

    // User cancels during editing
    await createSet(tokenThemesSetsSidebar, "core/colors/dark", "Escape");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "light-renamed",
      "dark",
    ]);

    // Creates nesting by renaming set with double click
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "light-renamed" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Rename").click();
    await changeSetInput(tokenThemesSetsSidebar, "nested/light");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "nested",
      "light",
      "dark",
    ]);

    // Create set in group
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Add set to this group").click();
    await changeSetInput(tokenThemesSetsSidebar, "sizes/small");

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "nested",
      "light",
      "dark",
      "sizes",
      "small",
    ]);

    // User deletes set
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "nested" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Delete").click();

    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "dark",
      "sizes",
      "small",
    ]);
  });

  test("Fold/Unfold set", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenSetGroupItems } =
      await setupTokensFile(page);

    await expect(tokenThemesSetsSidebar).toBeVisible();

    const darkSet = tokenThemesSetsSidebar.getByRole("button", {
      name: "dark",
      exact: true,
    });

    await expect(darkSet).toBeVisible();

    const setGroup = await tokenSetGroupItems
      .filter({ hasText: "LightDark" })
      .first();

    await setGroup.getByRole("button").filter({ title: "Collapse" }).click();

    await expect(darkSet).toHaveCount(0);
  });

  test("Change current theme", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenSetItems } =
      await setupTokensFile(page);

    await expect(tokenSetItems.nth(1)).toHaveAttribute("aria-checked", "true");
    await expect(tokenSetItems.nth(2)).toHaveAttribute("aria-checked", "false");

    await tokenThemesSetsSidebar.getByTestId("theme-select").click();
    await page
      .getByTestId("theme-select-dropdown")
      .getByRole("option", { name: "Dark", exact: true })
      .click();

    await expect(tokenSetItems.nth(1)).toHaveAttribute("aria-checked", "false");
    await expect(tokenSetItems.nth(2)).toHaveAttribute("aria-checked", "true");
  });
});

test.describe("Tokens: Themes modal", () => {
  test("Delete theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await expect(
      tokenThemeUpdateCreateModal.getByRole("button", { name: "Delete theme" }),
    ).toHaveCount(2);

    await tokenThemeUpdateCreateModal
      .getByRole("button", { name: "Delete theme" })
      .first()
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByRole("button", { name: "Delete theme" }),
    ).toHaveCount(1);
  });

  test("Add new theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "Add new theme",
      })
      .click();

    await tokenThemeUpdateCreateModal
      .getByLabel("Group")
      .fill("New Group name");
    await tokenThemeUpdateCreateModal
      .getByLabel("Theme")
      .fill("New Theme name");

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "Save theme",
      })
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("New Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("New Group name"),
    ).toBeVisible();
  });

  test("Edit theme", async ({ page }) => {
    const { tokenThemeUpdateCreateModal, workspacePage } =
      await setupTokensFile(page);

    workspacePage.openTokenThemesModal();

    await expect(
      tokenThemeUpdateCreateModal.getByText("no sets"),
    ).not.toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("3 active sets"),
    ).toHaveCount(2);

    await tokenThemeUpdateCreateModal
      .getByText("3 active sets")
      .first()
      .click();

    await tokenThemeUpdateCreateModal
      .getByLabel("Theme")
      .fill("Changed Theme name");
    await tokenThemeUpdateCreateModal
      .getByLabel("Group")
      .fill("Changed Group name");

    const checkboxes = await tokenThemeUpdateCreateModal
      .locator('[role="checkbox"]')
      .all();

    for (const checkbox of checkboxes) {
      const isChecked = await checkbox.getAttribute("aria-checked");

      if (isChecked === "true") {
        await checkbox.click();
      }
    }

    await tokenThemeUpdateCreateModal
      .getByRole("button", {
        name: "Save theme",
      })
      .click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Group name"),
    ).toBeVisible();
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

      await tokensSidebar
        .getByRole("button")
        .filter({ hasText: "Color" })
        .click();

      await tokensSidebar
        .getByRole("button", { name: "colors.black" })
        .click({ button: "right" });
      await tokenContextMenuForToken.getByText("Fill").click();
      const inputColor = await workspacePage.page
        .getByPlaceholder("Mixed")
        .nth(2);

      await expect(inputColor).toHaveValue("000000");
    });
  });
});
