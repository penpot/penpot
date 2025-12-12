import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { BaseWebSocketPage } from "../pages/BaseWebSocketPage";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

const setupEmptyTokensFile = async (page, options = {}) => {
  const { flags = [] } = options;

  const workspacePage = new WorkspacePage(page);
  if (flags.length > 0) {
    await workspacePage.mockConfigFlags(flags);
  }

  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "get-team?id=*",
    "workspace/get-team-tokens.json",
  );

  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "c7ce0794-0992-8105-8004-38f280443849",
    pageId: "66697432-c33d-8055-8006-2c62cc084cad",
  });

  const tokensTabButton = page.getByRole("tab", { name: "Tokens" });
  await tokensTabButton.click();

  return {
    workspacePage,
    tokenThemeUpdateCreateModal: workspacePage.tokenThemeUpdateCreateModal,
    tokensUpdateCreateModal: workspacePage.tokensUpdateCreateModal,
    tokenThemesSetsSidebar: workspacePage.tokenThemesSetsSidebar,
    tokenSetItems: workspacePage.tokenSetItems,
    tokenSetGroupItems: workspacePage.tokenSetGroupItems,
    tokenContextMenuForSet: workspacePage.tokenContextMenuForSet,
  };
};

const setupTokensFile = async (page, options = {}) => {
  const {
    file = "workspace/get-file-tokens.json",
    fileFragment = "workspace/get-file-fragment-tokens.json",
    flags = [],
  } = options;

  const workspacePage = new WorkspacePage(page);
  if (flags.length > 0) {
    await workspacePage.mockConfigFlags(flags);
  }

  await workspacePage.setupEmptyFile();
  await workspacePage.mockRPC(
    "get-team?id=*",
    "workspace/get-team-tokens.json",
  );
  await workspacePage.mockRPC(/get\-file\?/, file);
  await workspacePage.mockRPC(/get\-file\-fragment\?/, fileFragment);
  await workspacePage.mockRPC(
    "update-file?id=*",
    "workspace/update-file-create-rect.json",
  );

  await workspacePage.goToWorkspace({
    fileId: "c7ce0794-0992-8105-8004-38f280443849",
    pageId: "66697432-c33d-8055-8006-2c62cc084cad",
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

const setupTypographyTokensFile = async (page, options = {}) => {
  return setupTokensFile(page, {
    file: "workspace/get-file-typography-tokens.json",
    fileFragment: "workspace/get-file-fragment-typography-tokens.json",
    ...options,
  });
};

const checkInputFieldWithError = async (
  tokenThemeUpdateCreateModal,
  inputLocator,
) => {
  await expect(inputLocator).toHaveAttribute("aria-invalid", "true");

  const errorMessageId = await inputLocator.getAttribute("aria-describedby");
  await expect(
    tokenThemeUpdateCreateModal.locator(`#${errorMessageId}`),
  ).toBeVisible();
};

const checkInputFieldWithoutError = async (
  tokenThemeUpdateCreateModal,
  inputLocator,
) => {
  expect(await inputLocator.getAttribute("aria-invalid")).toBeNull();
  expect(await inputLocator.getAttribute("aria-describedby")).toBeNull();
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
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Color" })
      .click();

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

    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Color" })
      .click();
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
    await expect(
      tokensTabPanel.getByRole("button", { name: "#ff0000" }),
    ).toHaveCount(2);

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
    await tokensTabPanel
      .getByRole("button", { name: "Add token: Dimensions" })
      .click();

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

    await tokensUpdateCreateModal.getByRole("button", {name: "Save"}).click();

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

    const lightDarkSetGroup = tokenThemeUpdateCreateModal.getByTestId(
      "tokens-set-group-item",
      {
        name: "LightDark",
        exact: true,
      },
    );
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
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Color" })
      .click();

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

  test("User cant create regular token with value missing", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal } = await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Color" })
      .click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // Initially submit button should be disabled
    await expect(submitButton).toBeDisabled();

    // Fill in name but leave value empty
    await nameField.click();
    await nameField.fill("color.primary");

    // Submit button should remain disabled when value is empty
    await expect(submitButton).toBeDisabled();
  });

  test("User changes color token color while keeping custom color space", async ({
    page,
  }) => {
    const { workspacePage, tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Color" })
      .click();

    await expect(tokensUpdateCreateModal).toBeVisible();
    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await valueField.click();
    await valueField.fill("hsv(1,1,1)");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: #ff0400"),
    ).toBeVisible();

    const colorBullet = tokensUpdateCreateModal.getByTestId(
      "token-form-color-bullet",
    );
    await colorBullet.click();

    const valueSaturationSelector = tokensUpdateCreateModal.getByTestId(
      "value-saturation-selector",
    );
    await expect(valueSaturationSelector).toBeVisible();

    // Check if color space doesnt get overwritten when changing color via the picker
    // Not testing for exact value to avoid flakiness of px click
    await valueSaturationSelector.click({ position: { x: 100, y: 100 } });
    await expect(valueField).not.toHaveValue("hsv(1,1,1)");
    await expect(valueField).toHaveValue(/^hsv.*$/);

    // Clearing the input field should pick hex
    await valueField.fill("");
    // TODO: We need to fix this translation
    await expect(
      tokensUpdateCreateModal.getByText("Token value cannot be empty"),
    ).toBeVisible();
    await valueSaturationSelector.click({ position: { x: 50, y: 50 } });
    await expect(valueField).toHaveValue(/^#[A-Fa-f\d]+$/);

    // Changing opacity for hex values converts to rgba
    const sliderOpacity = tokensUpdateCreateModal.getByTestId("slider-opacity");
    await sliderOpacity.click({ position: { x: 50, y: 0 } });
    await expect(valueField).toHaveValue(/^rgba(.*)$/);

    // Changing color now will stay in rgba
    await valueSaturationSelector.click({ position: { x: 0, y: 0 } });
    await expect(valueField).toHaveValue(/^rgba(.*)$/);
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

  const assertEmptySetsList = async (el) => {
    const buttons = await el.getByRole("button").allTextContents();
    const filteredButtons = buttons.filter((text) => text === "Create one.");
    await expect(filteredButtons.length).toEqual(2); // We assume there are no themes, so we have two "Create one" buttons.
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

    // User deletes all sets
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .click({ button: "right" });
    await expect(tokenContextMenuForSet).toBeVisible();
    await tokenContextMenuForSet.getByText("Delete").click();

    await assertEmptySetsList(tokenThemesSetsSidebar);
  });

  test("User can create & edit sets and set groups with an identical name", async ({
    page,
  }) => {
    const { tokenThemesSetsSidebar, tokenContextMenuForSet } =
      await setupEmptyTokensFile(page);

    const tokensTabButton = tokenThemesSetsSidebar
      .getByRole("button", { name: "Add set" })
      .click();

    await createSet(tokenThemesSetsSidebar, "core/colors");
    await createSet(tokenThemesSetsSidebar, "core");
    await assertSetsList(tokenThemesSetsSidebar, ["core", "colors", "core"]);
    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .nth(0)
      .dblclick();
    await changeSetInput(tokenThemesSetsSidebar, "core-group-renamed");
    await assertSetsList(tokenThemesSetsSidebar, [
      "core-group-renamed",
      "colors",
      "core",
    ]);

    await page.keyboard.press(`ControlOrMeta+z`);
    await assertSetsList(tokenThemesSetsSidebar, ["core", "colors", "core"]);

    await tokenThemesSetsSidebar
      .getByRole("button", { name: "core" })
      .nth(1)
      .dblclick();
    await changeSetInput(tokenThemesSetsSidebar, "core-set-renamed");
    await assertSetsList(tokenThemesSetsSidebar, [
      "core",
      "colors",
      "core-set-renamed",
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

    const setCollapsable = await setGroup
      .getByTestId("tokens-set-group-collapse")
      .first();

    await setCollapsable.click();

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

  test("Add new theme in empty file", async ({ page }) => {
    const { tokenThemesSetsSidebar, tokenThemeUpdateCreateModal } =
      await setupEmptyTokensFile(page);

    await tokenThemesSetsSidebar
      .getByRole("button", { name: "Create one." })
      .first()
      .click();

    await expect(tokenThemeUpdateCreateModal).toBeVisible();

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("New Group name");
    await nameInput.fill("New Theme name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("New Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("New Group name"),
    ).toBeVisible();
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

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("Core"); // Invalid because "Core / Light" theme already exists
    await nameInput.fill("Light");

    await checkInputFieldWithError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).toBeDisabled();

    await groupInput.fill("New Group name");
    await nameInput.fill("New Theme name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

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

    const groupInput = tokenThemeUpdateCreateModal.getByLabel("Group");
    const nameInput = tokenThemeUpdateCreateModal.getByLabel("Theme");
    const saveButton = tokenThemeUpdateCreateModal.getByRole("button", {
      name: "Save theme",
    });

    await groupInput.fill("Core"); // Invalid because "Core / Dark" theme already exists
    await nameInput.fill("Dark");

    await checkInputFieldWithError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).toBeDisabled();

    await groupInput.fill("Core"); // Valid because "Core / Light" theme already exists
    await nameInput.fill("Light"); // but it's the same theme we are editing

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).not.toBeDisabled();

    await nameInput.fill("Changed Theme name"); // New names should be also valid
    await groupInput.fill("Changed Group name");

    await checkInputFieldWithoutError(tokenThemeUpdateCreateModal, nameInput);
    await expect(saveButton).not.toBeDisabled();

    expect(await nameInput.getAttribute("aria-invalid")).toBeNull();
    expect(await nameInput.getAttribute("aria-describedby")).toBeNull();

    const checkboxes = await tokenThemeUpdateCreateModal
      .locator('[role="checkbox"]')
      .all();

    for (const checkbox of checkboxes) {
      const isChecked = await checkbox.getAttribute("aria-checked");

      if (isChecked === "true") {
        await checkbox.click();
      }
    }

    const firstButton = await tokenThemeUpdateCreateModal
      .getByTestId("tokens-set-item")
      .first();

    await firstButton.click();

    await expect(saveButton).not.toBeDisabled();

    await saveButton.click();

    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Theme name"),
    ).toBeVisible();
    await expect(
      tokenThemeUpdateCreateModal.getByText("Changed Group name"),
    ).toBeVisible();
  });
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

    await expect(
      workspacePage.page.getByLabel("Name: colors.black"),
    ).toBeVisible();
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

  test("User edits typography token and all fields are valid", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar, tokensSidebar } =
      await setupTypographyTokensFile(page);

    await tokensSidebar
      .getByRole("button")
      .filter({ hasText: "Typography" })
      .click();

    // Open edit modal for "Full" typography token
    const token = tokensSidebar.getByRole("button", { name: "Full" });
    await token.click({ button: "right" });
    await page.getByText("Edit token").click();

    // Modal opens
    await expect(tokensUpdateCreateModal).toBeVisible();

    const saveButton = tokensUpdateCreateModal.getByRole("button", {
      name: /save/i,
    });

    // Fill font-family to verify to verify that input value doesn't get split into list of characters
    const fontFamilyField = tokensUpdateCreateModal
      .getByLabel("Font family")
      .first();
    await fontFamilyField.fill("OneWord");

    // Invalidate incorrect values for font size
    const fontSizeField = tokensUpdateCreateModal.getByLabel(/Font Size/i);
    await fontSizeField.fill("invalid");
    await expect(
      tokensUpdateCreateModal.getByText(/Invalid token value:/),
    ).toBeVisible();
    await expect(saveButton).toBeDisabled();

    // Show error with line-height depending on invalid font-size
    await fontSizeField.fill("");
    await expect(saveButton).toBeDisabled();

    // Fill in values for all fields and verify they persist when switching tabs
    await fontSizeField.fill("16");

    const fontWeightField = tokensUpdateCreateModal.getByLabel(/Font Weight/i);
    const letterSpacingField =
      tokensUpdateCreateModal.getByLabel(/Letter Spacing/i);
    const lineHeightField = tokensUpdateCreateModal.getByLabel(/Line Height/i);
    const textCaseField = tokensUpdateCreateModal.getByLabel(/Text Case/i);
    const textDecorationField =
      tokensUpdateCreateModal.getByLabel(/Text Decoration/i);

    // Capture all values before switching tabs
    const originalValues = {
      fontSize: await fontSizeField.inputValue(),
      fontFamily: await fontFamilyField.inputValue(),
      fontWeight: await fontWeightField.inputValue(),
      letterSpacing: await letterSpacingField.inputValue(),
      lineHeight: await lineHeightField.inputValue(),
      textCase: await textCaseField.inputValue(),
      textDecoration: await textDecorationField.inputValue(),
    };

    // Switch to reference tab and back to composite tab
    const referenceTabButton =
      tokensUpdateCreateModal.getByTestId("reference-opt");
    await referenceTabButton.click();

    // Empty reference tab should be disabled
    await expect(saveButton).toBeDisabled();

    const compositeTabButton =
      tokensUpdateCreateModal.getByTestId("composite-opt");
    await compositeTabButton.click();

    // Filled composite tab should be enabled
    await expect(saveButton).toBeEnabled();

    // Verify all values are preserved after switching tabs
    await expect(fontSizeField).toHaveValue(originalValues.fontSize);
    await expect(fontFamilyField).toHaveValue(originalValues.fontFamily);
    await expect(fontWeightField).toHaveValue(originalValues.fontWeight);
    await expect(letterSpacingField).toHaveValue(originalValues.letterSpacing);
    await expect(lineHeightField).toHaveValue(originalValues.lineHeight);
    await expect(textCaseField).toHaveValue(originalValues.textCase);
    await expect(textDecorationField).toHaveValue(
      originalValues.textDecoration,
    );

    await saveButton.click();

    // Modal should close, token should be visible (with new name) in sidebar
    await expect(tokensUpdateCreateModal).not.toBeVisible();
  });

  test("User cant submit empty typography token or reference", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar, tokensSidebar } =
      await setupTypographyTokensFile(page);

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Typography" })
      .click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    await nameField.fill("typography.empty");

    const valueField = tokensUpdateCreateModal.getByLabel("Font Size");

    // Insert a value and then delete it
    await valueField.fill("1");
    await valueField.fill("");

    // Submit button should be disabled when field is empty
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeDisabled();

    // Switch to reference tab, should not be submittable either
    const referenceTabButton =
      tokensUpdateCreateModal.getByTestId("reference-opt");
    await referenceTabButton.click();
    await expect(submitButton).toBeDisabled();
  });

  test("User adds typography token with reference", async ({ page }) => {
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar, tokensSidebar } =
      await setupTypographyTokensFile(page);

    const newTokenTitle = "NewReference";

    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });
    await tokensTabPanel
      .getByRole("button", { name: "Add Token: Typography" })
      .click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    await nameField.fill(newTokenTitle);

    const referenceTabButton =
      tokensUpdateCreateModal.getByTestId("reference-opt");
    referenceTabButton.click();

    const referenceField = tokensUpdateCreateModal.getByLabel("Reference");
    await referenceField.fill("{Full}");

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    const resolvedValue =
      await tokensUpdateCreateModal.getByText("Resolved value:");
    await expect(resolvedValue).toBeVisible();
    await expect(resolvedValue).toContainText("Font Family: 42dot Sans");
    await expect(resolvedValue).toContainText("Font Size: 100");
    await expect(resolvedValue).toContainText("Font Weight: 300");
    await expect(resolvedValue).toContainText("Letter Spacing: 2");
    await expect(resolvedValue).toContainText("Text Case: uppercase");
    await expect(resolvedValue).toContainText("Text Decoration: underline");

    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(tokensUpdateCreateModal).not.toBeVisible();

    const newToken = tokensSidebar.getByRole("button", {
      name: newTokenTitle,
    });

    await expect(newToken).toBeVisible();
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
      await nameField.fill("shadow.primary");

      // User adds first shadow with a color from the color ramp
      const firstShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-0",
      );
      await expect(firstShadowFields).toBeVisible();

      // Fill in the shadow values
      const offsetXInput = firstShadowFields.getByLabel("X");
      const offsetYInput = firstShadowFields.getByLabel("Y");
      const blurInput = firstShadowFields.getByLabel("Blur");
      const spreadInput = firstShadowFields.getByLabel("Spread");

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
      const colorInput = firstShadowFields.getByLabel("Color");
      const firstColorValue = await colorInput.inputValue();
      await expect(firstColorValue).toMatch(/^rgb(.*)$/);

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
      const colorInput = firstShadowFields.getByLabel("Color");
      const firstColorValue = await colorInput.inputValue();

      // User adds a second shadow
      const addButton = firstShadowFields.getByTestId("shadow-add-button-0");
      await addButton.click();

      const secondShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-1",
      );
      await expect(secondShadowFields).toBeVisible();

      // User adds a third shadow
      const addButton2 = secondShadowFields.getByTestId("shadow-add-button-1");
      await addButton2.click();

      const thirdShadowFields = tokensUpdateCreateModal.getByTestId(
        "shadow-input-fields-2",
      );
      await expect(thirdShadowFields).toBeVisible();

      // User adds values for the third shadow
      const thirdOffsetXInput = thirdShadowFields.getByLabel("X");
      const thirdOffsetYInput = thirdShadowFields.getByLabel("Y");
      const thirdBlurInput = thirdShadowFields.getByLabel("Blur");
      const thirdSpreadInput = thirdShadowFields.getByLabel("Spread");
      const thirdColorInput = thirdShadowFields.getByLabel("Color");

      await thirdOffsetXInput.fill("10");
      await thirdOffsetYInput.fill("10");
      await thirdBlurInput.fill("20");
      await thirdSpreadInput.fill("5");
      await thirdColorInput.fill("#FF0000");

      // User removes the 2nd shadow
      const removeButton2 = secondShadowFields.getByTestId(
        "shadow-remove-button-1",
      );
      await removeButton2.click();

      // Verify second shadow is removed
      await expect(
        secondShadowFields.getByTestId("shadow-add-button-3"),
      ).not.toBeVisible();

      // Verify that the first shadow kept its values
      const firstOffsetXValue = await firstShadowFields
        .getByLabel("X")
        .inputValue();
      const firstOffsetYValue = await firstShadowFields
        .getByLabel("Y")
        .inputValue();
      const firstBlurValue = await firstShadowFields
        .getByLabel("Blur")
        .inputValue();
      const firstSpreadValue = await firstShadowFields
        .getByLabel("Spread")
        .inputValue();
      const firstColorValueAfter = await firstShadowFields
        .getByLabel("Color")
        .inputValue();

      await expect(firstOffsetXValue).toBe("2");
      await expect(firstOffsetYValue).toBe("2");
      await expect(firstBlurValue).toBe("4");
      await expect(firstSpreadValue).toBe("0");
      await expect(firstColorValueAfter).toBe(firstColorValue);

      // Verify that the third shadow (now second) kept its values
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
        .getByLabel("Blur")
        .inputValue();
      const secondSpreadValue = await newSecondShadowFields
        .getByLabel("Spread")
        .inputValue();
      const secondColorValue = await newSecondShadowFields
        .getByLabel("Color")
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
      const colorInput = firstShadowFields.getByLabel("Color");
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
        .getByLabel("Blur")
        .inputValue();
      const restoredFirstSpread = await firstShadowFields
        .getByLabel("Spread")
        .inputValue();
      const restoredFirstColor = await firstShadowFields
        .getByLabel("Color")
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
        .getByLabel("Blur")
        .inputValue();
      const restoredSecondSpread = await newSecondShadowFields
        .getByLabel("Spread")
        .inputValue();
      const restoredSecondColor = await newSecondShadowFields
        .getByLabel("Color")
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

      // Verify token appears in sidebar
      const shadowToken = tokensSidebar.getByRole("button", {
        name: "shadow.primary",
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
});
