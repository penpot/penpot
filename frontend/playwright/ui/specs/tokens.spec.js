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
    tokensSidebar: workspacePage.tokensSidebar,
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

const checkInputFieldWithoutError = async (inputLocator) => {
  expect(await inputLocator.getAttribute("aria-invalid")).toBeNull();
  expect(await inputLocator.getAttribute("aria-describedby")).toBeNull();
};

const testTokenCreationFlow = async (
  page,
  {
    tokenLabel,
    namePlaceholder,
    valuePlaceholder,
    validValue,
    invalidValue,
    selfReferenceValue,
    missingReferenceValue,
    secondValidValue,
    resolvedValueText,
    secondResolvedValueText,
  },
) => {
  const invalidValueError = "Invalid token value";
  const emptyNameError = "Name should be at least 1 character";
  const selfReferenceError = "Token has self reference";
  const missingReferenceError = "Missing token references";

  const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
    await setupEmptyTokensFile(page);

  // Open modal
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  const addTokenButton = tokensTabPanel.getByRole("button", {
    name: `Add Token: ${tokenLabel}`,
  });

  await addTokenButton.click();
  await expect(tokensUpdateCreateModal).toBeVisible();

  // Placeholder checks
  await expect(
    tokensUpdateCreateModal.getByPlaceholder(namePlaceholder),
  ).toBeVisible();
  await expect(
    tokensUpdateCreateModal.getByPlaceholder(valuePlaceholder),
  ).toBeVisible();

  const nameField = tokensUpdateCreateModal.getByLabel("Name");
  const valueField = tokensUpdateCreateModal.getByLabel("Value");
  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });

  // 1. Name filled + empty value → disabled
  await nameField.fill("my-token");
  await expect(submitButton).toBeDisabled();

  // 2. Invalid value → disabled + error message
  await valueField.fill(invalidValue);

  const invalidValueErrorNode =
    tokensUpdateCreateModal.getByText(invalidValueError);

  await expect(invalidValueErrorNode).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 3. Empty name → disabled + error message
  await nameField.fill("");

  const emptyNameErrorNode = tokensUpdateCreateModal.getByText(emptyNameError);

  await expect(emptyNameErrorNode).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 4. Self reference → disabled + error message
  await nameField.fill("my-token");
  await valueField.fill(selfReferenceValue);

  const selfRefErrorNode =
    tokensUpdateCreateModal.getByText(selfReferenceError);

  await expect(selfRefErrorNode).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 5. Missing reference → disabled + error message
  await valueField.fill(missingReferenceValue);

  const missingRefErrorNode = tokensUpdateCreateModal.getByText(
    missingReferenceError,
  );

  await expect(missingRefErrorNode).toBeVisible();
  await expect(submitButton).toBeDisabled();

  //
  // ------- SUCCESSFUL CREATION -------
  //

  // 6. Basic valid value → enabled
  await valueField.fill(validValue);
  await expect(
    tokensUpdateCreateModal.getByText(resolvedValueText),
  ).toBeVisible();
  await expect(submitButton).toBeEnabled();

  await submitButton.click();

  await expect(
    tokensTabPanel.getByRole("button", { name: "my-token" }),
  ).toBeEnabled();

  //
  // ------- SECOND TOKEN WITH VALID REFERENCE -------
  //

  await addTokenButton.click();

  await nameField.fill("my-token-2");
  await valueField.fill(secondValidValue);

  await expect(
    tokensUpdateCreateModal.getByText(secondResolvedValueText),
  ).toBeVisible();
  await expect(submitButton).toBeEnabled();

  await submitButton.click();

  await expect(
    tokensTabPanel.getByRole("button", { name: "my-token-2" }),
  ).toBeEnabled();
};

const unfoldTokenTree = async (tokensTabPanel, type, tokenName) => {
  const tokenSegments = tokenName.split(".");
  const tokenFolderTree = tokenSegments.slice(0, -1);
  const tokenLeafName = tokenSegments.pop();

  const typeParentWrapper = tokensTabPanel.getByTestId(`section-${type}`);
  const typeSectionButton = typeParentWrapper
    .getByRole("button", {
      name: type,
    })
    .first();

  const isSectionExpanded =
    await typeSectionButton.getAttribute("aria-expanded");

  if (isSectionExpanded === "false") {
    await typeSectionButton.click();
  }

  for (const segment of tokenFolderTree) {
    const segmentButton = typeParentWrapper
      .getByRole("listitem")
      .getByRole("button", { name: segment })
      .first();

    const isExpanded = await segmentButton.getAttribute("aria-expanded");
    if (isExpanded === "false") {
      await segmentButton.click();
    }
  }

  await expect(
    typeParentWrapper.getByRole("button", {
      name: tokenLeafName,
    }),
  ).toBeEnabled();
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

  test("User creates border radius token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Border Radius",
      namePlaceholder: "Enter border radius token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates dimensions token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Dimensions",
      namePlaceholder: "Enter dimensions token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates font size token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Font Size",
      namePlaceholder: "Enter font size token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates letter spacing token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Letter spacing",
      namePlaceholder: "Enter letter spacing token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates number token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Number",
      namePlaceholder: "Enter number token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates rotation token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Rotation",
      namePlaceholder: "Enter rotation token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates sizing token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Sizing",
      namePlaceholder: "Enter sizing token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates spacing token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Spacing",
      namePlaceholder: "Enter spacing token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates stroke width token", async ({ page }) => {
    await testTokenCreationFlow(page, {
      tokenLabel: "Stroke width",
      namePlaceholder: "Enter stroke width token name",
      valuePlaceholder: "Enter a value or alias with {alias}",
      invalidValue: "red",
      validValue: "2 + 3",
      selfReferenceValue: "{my-token}",
      missingReferenceValue: "{missing-token}",
      secondValidValue: "{my-token} - 2",
      resolvedValueText: "Resolved value: 5",
      secondResolvedValueText: "Resolved value: 3",
    });
  });

  test("User creates color token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const invalidValueError = "Invalid color value";
    const emptyNameError = "Name should be at least 1 character";
    const selfReferenceError = "Token has self reference";
    const missingReferenceError = "Missing token references";
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar, tokensSidebar } =
      await setupEmptyTokensFile(page);

    await tokensSidebar
      .getByRole("button", { name: "Add Token: Color" })
      .click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    // Placeholder checks
    await expect(
      tokensUpdateCreateModal.getByPlaceholder("Enter color token name"),
    ).toBeVisible();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "Enter a value or alias with {alias}",
      ),
    ).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.click();
    await nameField.fill("color.primary");
    await expect(submitButton).toBeDisabled();

    // 2. Invalid value → disabled + error message
    await valueField.fill("1");
    const invalidValueErrorNode =
      tokensUpdateCreateModal.getByText(invalidValueError);
    await expect(invalidValueErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 3. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 4. Self reference → disabled + error message
    await nameField.fill("color.primary");
    await valueField.fill("{color.primary}");

    const selfRefErrorNode =
      tokensUpdateCreateModal.getByText(selfReferenceError);

    await expect(selfRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 5. Missing reference → disabled + error message
    await valueField.fill("{missing-reference}");

    const missingRefErrorNode = tokensUpdateCreateModal.getByText(
      missingReferenceError,
    );

    await expect(missingRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // valid value
    await valueField.fill("red");
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await unfoldTokenTree(tokensSidebar, "color", "color.primary");

    // Create token referencing the previous one with keyboard

    await tokensSidebar
      .getByRole("button", { name: "Add Token: Color" })
      .click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    await nameField.click();
    await nameField.fill("secondary");
    await nameField.press("Tab");

    await valueField.click();
    await valueField.fill("{color.primary}");

    await expect(submitButton).toBeEnabled();
    await submitButton.press("Enter");

    await expect(
      tokensSidebar.getByRole("button", {
        name: "secondary",
      }),
    ).toBeEnabled();

    // Tokens tab panel should have two tokens with the color red / #ff0000
    await expect(
      tokensSidebar.getByRole("button", { name: "#ff0000" }),
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

    // Check color picker
    await tokensSidebar
      .getByRole("button", { name: "Add Token: Color" })
      .click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    await nameField.click();
    await nameField.fill("color.tertiary");
    await nameField.press("Tab");
    const colorSwatch = tokensUpdateCreateModal.getByTestId(
      "token-form-color-bullet",
    );
    await colorSwatch.click();
    const rampSelector = tokensUpdateCreateModal.getByTestId(
      "value-saturation-selector",
    );
    await expect(rampSelector).toBeVisible();
    await rampSelector.click({ position: { x: 50, y: 50 } });

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value:"),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();

    // Check color with opacity
    const sliderOpacity = tokensUpdateCreateModal.getByTestId("slider-opacity");
    await sliderOpacity.click({ position: { x: 50, y: 0 } });
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: rgba("),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();

    // Check hslv
    await colorSwatch.click();
    await expect(rampSelector).not.toBeVisible();
    await valueField.fill("hsv(1,1,1)");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: #ff0400"),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();
  });

  test("User creates a font family token", async ({ page }) => {
    const emptyNameError = "Name should be at least 1 character";
    const selfReferenceError = "Token has self reference";
    const missingReferenceError = "Missing token references";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Font Family`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    // Placeholder checks
    await expect(
      tokensUpdateCreateModal.getByPlaceholder("Enter font family token name"),
    ).toBeVisible();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "Enter a value or alias with {alias}",
      ),
    ).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 2. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 4. Self reference → disabled + error message
    await nameField.fill("my-token");
    await valueField.fill("{my-token}");

    const selfRefErrorNode =
      tokensUpdateCreateModal.getByText(selfReferenceError);

    await expect(selfRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 5. Missing reference → disabled + error message
    await valueField.fill("{missing-token}");

    const missingRefErrorNode = tokensUpdateCreateModal.getByText(
      missingReferenceError,
    );

    await expect(missingRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    //
    // ------- SUCCESSFUL CREATION -------
    //

    // 6. Basic valid value → enabled
    await valueField.fill("Times new roman");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: Times new roman"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN DROPDOWN OPTION -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-2");
    const selectDropdown = tokensUpdateCreateModal.getByRole("button", {
      name: "Select font family",
    });
    await selectDropdown.click();

    const fontOption = tokensUpdateCreateModal.getByText("ABeeZee");
    await expect(fontOption).toBeVisible();

    await fontOption.click();

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: ABeeZee"),
    ).toBeVisible();
    await selectDropdown.click();

    const searchField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Search font",
    });
    await searchField.fill("alme");
    const fontOption2 = tokensUpdateCreateModal.getByText("Almendra Display");
    await expect(fontOption2).toBeVisible();
    await fontOption2.click();

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: Almendra Display"),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();

    //
    // ------- THIRD TOKEN REFERENCE OPTION -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-3");
    await valueField.fill("{my-token}");

    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-3" }),
    ).toBeEnabled();
  });

  test("User creates font weight token", async ({ page }) => {
    const invalidValueError =
      "Invalid font weight value: use numeric values (100-950) or standard names (thin, light, regular, bold, etc.) optionally followed by 'Italic'";
    const emptyNameError = "Name should be at least 1 character";
    const selfReferenceError = "Token has self reference";
    const missingReferenceError = "Missing token references";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Font Weight`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    // Placeholder checks
    await expect(
      tokensUpdateCreateModal.getByPlaceholder("Enter font weight token name"),
    ).toBeVisible();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "Font weight (300, Bold Italic...) or an {alias}",
      ),
    ).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 2. Invalid value → disabled + error message
    await valueField.fill("red");

    const invalidValueErrorNode =
      tokensUpdateCreateModal.getByText(invalidValueError);

    await expect(invalidValueErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 3. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 4. Self reference → disabled + error message
    await nameField.fill("my-token");
    await valueField.fill("{my-token}");

    const selfRefErrorNode =
      tokensUpdateCreateModal.getByText(selfReferenceError);

    await expect(selfRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 5. Missing reference → disabled + error message
    await valueField.fill("{missing-reference}");

    const missingRefErrorNode = tokensUpdateCreateModal.getByText(
      missingReferenceError,
    );

    await expect(missingRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    //
    // ------- SUCCESSFUL CREATION -------
    //

    // 6. Basic valid value → enabled
    await valueField.fill("300");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 300"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN WITH VALID REFERENCE -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-2");
    await valueField.fill("{my-token} + 200");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 500"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();

    //
    // ------- THIRD TOKEN WITH NAMED FONT WEIGHT -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-3");
    await valueField.fill("bold");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: bold"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-3" }),
    ).toBeEnabled();
  });

  test("User creates text case token", async ({ page }) => {
    const invalidValueError =
      "Invalid token value: only none, Uppercase, Lowercase or Capitalize are accepted";
    const emptyNameError = "Name should be at least 1 character";
    const selfReferenceError = "Token has self reference";
    const missingReferenceError = "Missing token references";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Text Case`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    // Placeholder checks
    await expect(
      tokensUpdateCreateModal.getByPlaceholder("Enter text case token name"),
    ).toBeVisible();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "none | uppercase | lowercase | capitalize or {alias}",
      ),
    ).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 2. Invalid value → disabled + error message
    await valueField.fill("red");

    const invalidValueErrorNode =
      tokensUpdateCreateModal.getByText(invalidValueError);

    await expect(invalidValueErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 3. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 4. Self reference → disabled + error message
    await nameField.fill("my-token");
    await valueField.fill("{my-token}");

    const selfRefErrorNode =
      tokensUpdateCreateModal.getByText(selfReferenceError);

    await expect(selfRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 5. Missing reference → disabled + error message
    await valueField.fill("{missing-reference}");

    const missingRefErrorNode = tokensUpdateCreateModal.getByText(
      missingReferenceError,
    );

    await expect(missingRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    //
    // ------- SUCCESSFUL CREATION -------
    //

    // 6. Basic valid value → enabled
    await valueField.fill("uppercase");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: uppercase"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN WITH VALID REFERENCE -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-2");
    await valueField.fill("{my-token}");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: uppercase"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();
  });

  test("User creates text decoration token", async ({ page }) => {
    const invalidValueError =
      "Invalid token value: only none, underline and strike-through are accepted";
    const emptyNameError = "Name should be at least 1 character";
    const selfReferenceError = "Token has self reference";
    const missingReferenceError = "Missing token references";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Text Decoration`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    // Placeholder checks
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "Enter text decoration token name",
      ),
    ).toBeVisible();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "none | underline | strike-through or {alias}",
      ),
    ).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 2. Invalid value → disabled + error message
    await valueField.fill("red");

    const invalidValueErrorNode =
      tokensUpdateCreateModal.getByText(invalidValueError);

    await expect(invalidValueErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 3. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 4. Self reference → disabled + error message
    await nameField.fill("my-token");
    await valueField.fill("{my-token}");

    const selfRefErrorNode =
      tokensUpdateCreateModal.getByText(selfReferenceError);

    await expect(selfRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    // 5. Missing reference → disabled + error message
    await valueField.fill("{missing-reference}");

    const missingRefErrorNode = tokensUpdateCreateModal.getByText(
      missingReferenceError,
    );

    await expect(missingRefErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    //
    // ------- SUCCESSFUL CREATION -------
    //

    // 6. Basic valid value → enabled
    await valueField.fill("none");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: none"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN WITH VALID REFERENCE -------
    //

    await addTokenButton.click();

    await nameField.fill("my-token-2");
    await valueField.fill("{my-token}");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: none"),
    ).toBeVisible();
    await expect(submitButton).toBeEnabled();

    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();
  });

  test("User creates shadow token", async ({ page }) => {
    const emptyNameError = "Name should be at least 1 character";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page, { flags: ["enable-token-shadow"] });

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Shadow`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    await expect(
      tokensUpdateCreateModal.getByPlaceholder(
        "Enter a value or alias with {alias}",
      ),
    ).toBeVisible();

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
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Check default values
    await expect(offsetXField).toHaveValue("4");
    await expect(offsetYField).toHaveValue("4");
    await expect(blurField).toHaveValue("4");
    await expect(spreadField).toHaveValue("0");

    // 2. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 3. Invalid color → disabled + error message
    await colorField.fill("1");

    await expect(
      tokensUpdateCreateModal.getByText("Invalid color value: 1"),
    ).toBeVisible();

    await expect(submitButton).toBeDisabled();

    await colorField.fill("{missing-reference}");

    await expect(
      tokensUpdateCreateModal.getByText(
        "Missing token references: missing-reference",
      ),
    ).toBeVisible();

    // 4. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();

    //
    // ------- SUCCESSFUL FIELDS -------
    //

    // 5. Valid color → resolved

    await colorField.fill("red");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: #ff0000"),
    ).toBeVisible();
    const colorSwatch = tokensUpdateCreateModal.getByTestId(
      "token-form-color-bullet",
    );
    await colorSwatch.click();
    const rampSelector = tokensUpdateCreateModal.getByTestId(
      "value-saturation-selector",
    );
    await expect(rampSelector).toBeVisible();
    await rampSelector.click({ position: { x: 50, y: 50 } });

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value:"),
    ).toBeVisible();

    const sliderOpacity = tokensUpdateCreateModal.getByTestId("slider-opacity");
    await sliderOpacity.click({ position: { x: 50, y: 0 } });
    await expect(
      tokensUpdateCreateModal.getByRole("textbox", { name: "Color" }),
    ).toHaveValue(/rgba\s*\([^)]*\)/);

    // 6. Valid offset → resolved
    await offsetXField.fill("3 + 3");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 6"),
    ).toBeVisible();

    await offsetYField.fill("3 + 7");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 10"),
    ).toBeVisible();

    // 7. Valid blur → resolved

    await blurField.fill("3 + 1");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 4"),
    ).toBeVisible();

    // 8. Valid spread → resolved

    await blurField.fill("3 - 3");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 0"),
    ).toBeVisible();

    await nameField.fill("my-token");
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN WITH VALID REFERENCE -------
    //
    await addTokenButton.click();

    await nameField.fill("my-token-2");
    const referenceToggle =
      tokensUpdateCreateModal.getByTestId("reference-opt");
    const compositeToggle =
      tokensUpdateCreateModal.getByTestId("composite-opt");
    await referenceToggle.click();

    const referenceInput = tokensUpdateCreateModal.getByPlaceholder(
      "Enter a token shadow alias",
    );
    await expect(referenceInput).toBeVisible();

    await compositeToggle.click();
    await expect(colorField).toBeVisible();

    await referenceToggle.click();
    const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Reference",
    });
    await referenceField.fill("{my-token}");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Resolved value: - X: 6 - Y: 10 - Blur: 0 - Spread: 0 ",
      ),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();
    await submitButton.click();
    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();
  });

  test("User creates typography token", async ({ page }) => {
    const emptyNameError = "Name should be at least 1 character";
    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
      await setupEmptyTokensFile(page);

    // Open modal
    const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

    const addTokenButton = tokensTabPanel.getByRole("button", {
      name: `Add Token: Typography`,
    });

    await addTokenButton.click();
    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const fontFamilyField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Font family",
    });
    await expect(fontFamilyField).toBeVisible();
    const fontSizeField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Font size",
    });
    await expect(fontSizeField).toBeVisible();
    const fontWeightField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Font weight",
    });
    await expect(fontWeightField).toBeVisible();
    const lineHeightField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Line height",
    });
    await expect(lineHeightField).toBeVisible();
    const letterSpacingField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Letter spacing",
    });
    await expect(letterSpacingField).toBeVisible();

    const textCaseField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Text case",
    });
    await expect(textCaseField).toBeVisible();
    const textDecorationField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Text decoration",
    });
    await expect(textDecorationField).toBeVisible();

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // 1. Name filled + empty value → disabled
    await nameField.fill("my-token");
    await expect(submitButton).toBeDisabled();

    // 2. Empty name → disabled + error message
    await nameField.fill("");

    const emptyNameErrorNode =
      tokensUpdateCreateModal.getByText(emptyNameError);

    await expect(emptyNameErrorNode).toBeVisible();
    await expect(submitButton).toBeDisabled();
    await nameField.fill("my-token");

    //
    // ------- CHECK FIELDS -------
    //

    // 3. Font family

    const fontFamilyPlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "Font family or list of fonts separated by comma (,)",
    );
    await expect(fontFamilyPlaceholder).toBeVisible();
    await fontFamilyField.fill("red");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: red"),
    ).toBeVisible();

    const selectDropdown = tokensUpdateCreateModal.getByRole("button", {
      name: "Select font family",
    });
    await selectDropdown.click();

    const fontOption = tokensUpdateCreateModal.getByText("ABeeZee");
    await expect(fontOption).toBeVisible();

    await fontOption.click();

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: ABeeZee"),
    ).toBeVisible();

    await selectDropdown.click();

    const searchField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Search font",
    });
    await searchField.fill("alme");
    const fontOption2 = tokensUpdateCreateModal.getByText("Almendra Display");
    await expect(fontOption2).toBeVisible();
    await fontOption2.click();
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: Almendra Display"),
    ).toBeVisible();

    // 4. Font Size
    const fontSizePlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "Font size or {alias}",
    );
    await expect(fontSizePlaceholder).toBeVisible();
    await fontSizeField.fill("red");
    await expect(
      tokensUpdateCreateModal.getByText("Invalid token value: red"),
    ).toBeVisible();
    await fontSizeField.fill("24 + 2");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 26"),
    ).toBeVisible();

    // 4. Font Weight
    const fontWeightPlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "Font weight (300, Bold Italic...) or an {alias}",
    );
    await expect(fontWeightPlaceholder).toBeVisible();
    await fontWeightField.fill("2");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Invalid font weight value: use numeric values (100-950) or standard names (thin, light, regular, bold, etc.)",
      ),
    ).toBeVisible();
    await fontWeightField.fill("100 + 200");

    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 300"),
    ).toBeVisible();

    await fontWeightField.fill("bold");

    // 5. Line height

    const lineHegithPlaceholdewr = tokensUpdateCreateModal.getByPlaceholder(
      "Line height (multiplier, px, %) or {alias}",
    );
    await expect(lineHegithPlaceholdewr).toBeVisible();
    await fontSizeField.fill("");
    await lineHeightField.fill("2");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Line Height depends on Font Size. Add a Font Size to get the resolved value.",
      ),
    ).toBeVisible();

    await fontSizeField.fill("24 + 2");
    await lineHeightField.fill("2");

    // 6. Text case
    const textCasePlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "none | uppercase | lowercase | capitalize or {alias}",
    );
    await expect(textCasePlaceholder).toBeVisible();
    await textCaseField.fill("200");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Invalid token value: only none, Uppercase, Lowercase or Capitalize are accepted",
      ),
    ).toBeVisible();

    await textCaseField.fill("none");

    // 7. Letter spacing
    const letterSpacingPlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "Letter spacing or {alias}",
    );
    await expect(letterSpacingPlaceholder).toBeVisible();
    await letterSpacingField.fill("green");
    await expect(
      tokensUpdateCreateModal.getByText("Invalid token value: green"),
    ).toBeVisible();

    await letterSpacingField.fill("2.3");

    // 7. Text decoration

    const textDecorationPlaceholder = tokensUpdateCreateModal.getByPlaceholder(
      "none | underline | strike-through or {alias}",
    );
    await expect(textDecorationPlaceholder).toBeVisible();

    await textDecorationField.fill("200");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Invalid token value: only none, underline and strike-through are accepted",
      ),
    ).toBeVisible();

    await textDecorationField.fill("underline");

    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token" }),
    ).toBeEnabled();

    //
    // ------- SECOND TOKEN WITH VALID REFERENCE -------
    //
    await addTokenButton.click();

    await nameField.fill("my-token-2");

    const referenceToggle =
      tokensUpdateCreateModal.getByTestId("reference-opt");
    const compositeToggle =
      tokensUpdateCreateModal.getByTestId("composite-opt");

    await referenceToggle.click();

    const referenceInput = tokensUpdateCreateModal.getByPlaceholder(
      "Enter a token typography alias",
    );
    await expect(referenceInput).toBeVisible();

    await compositeToggle.click();
    await expect(
      tokensUpdateCreateModal.getByPlaceholder("Enter typography token name"),
    ).toBeVisible();

    await referenceToggle.click();
    const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Reference",
    });
    await referenceField.fill("{my-token}");
    await expect(
      tokensUpdateCreateModal.getByText(
        "Resolved value: - Font Family: Almendra Display - Font Weight: bold - Font Size: 26 - Text Case: none - Letter Spacing: 2.3 - Text Decoration: underline - Line Height: 2",
      ),
    ).toBeVisible();

    await expect(submitButton).toBeEnabled();
    await submitButton.click();
    await expect(
      tokensTabPanel.getByRole("button", { name: "my-token-2" }),
    ).toBeEnabled();
  });

  test("User edits token and auto created set show up in the sidebar", async ({
    page,
  }) => {
    const { tokensUpdateCreateModal, tokensSidebar, tokenContextMenuForToken } =
      await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    await unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("button", {
      name: "100",
    });
    await expect(colorToken).toBeVisible();
    await colorToken.click({ button: "right" });

    await expect(tokenContextMenuForToken).toBeVisible();
    await tokenContextMenuForToken.getByText("Edit token").click();

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    await nameField.pressSequentially(".changed");

    await tokensUpdateCreateModal.getByRole("button", { name: "Save" }).click();

    await expect(tokensUpdateCreateModal).not.toBeVisible();

    await unfoldTokenTree(tokensSidebar, "color", "colors.blue.100.changed");

    const colorTokenChanged = tokensSidebar.getByRole("button", {
      name: "changed",
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
    const { workspacePage, tokensUpdateCreateModal, tokensSidebar } =
      await setupEmptyTokensFile(page);

    await tokensSidebar
      .getByRole("button", { name: "Add Token: Color" })
      .click();

    // Create grouped color token with mouse

    await expect(tokensUpdateCreateModal).toBeVisible();

    const nameField = tokensUpdateCreateModal.getByLabel("Name");
    const valueField = tokensUpdateCreateModal.getByLabel("Value");

    await nameField.click();
    await nameField.fill("dark.primary");

    await valueField.click();
    await valueField.fill("red");

    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });
    await expect(submitButton).toBeEnabled();
    await submitButton.click();

    await unfoldTokenTree(tokensSidebar, "color", "dark.primary");

    await expect(tokensSidebar.getByLabel("primary")).toBeEnabled();
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
    const submitButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Save",
    });

    // Initially submit button should be disabled
    await expect(submitButton).toBeDisabled();

    // Fill in name but leave value empty
    await nameField.click();
    await nameField.fill("primary");

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

    unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("button", {
      name: "100",
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

    unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("button", {
      name: "100",
    });
    await expect(colorToken).toBeVisible();
    await colorToken.click({ button: "right" });

    await expect(tokenContextMenuForToken).toBeVisible();
    await tokenContextMenuForToken.getByText("Delete token").click();

    await expect(tokenContextMenuForToken).not.toBeVisible();
    await expect(colorToken).not.toBeVisible();
  });

  test("User fold/unfold color tokens", async ({ page }) => {
    const { tokensSidebar } = await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    const tokensColorGroup = tokensSidebar.getByRole("button", {
      name: "Color 92",
    });
    await expect(tokensColorGroup).toBeVisible();
    await tokensColorGroup.click();

    unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    const colorToken = tokensSidebar.getByRole("button", {
      name: "100",
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

    unfoldTokenTree(tokensSidebar, "color", "colors.black");

    await tokensSidebar
      .getByRole("button", { name: "black" })
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
    await expect(saveButton).toBeEnabled();

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
      tokensUpdateCreateModal.getByRole('button', { name: 'Use a reference' });
    referenceTabButton.click();

    const referenceField = tokensUpdateCreateModal.getByRole('textbox', {
      name: 'Reference'
    });
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

      nameField = tokensUpdateCreateModal.getByRole("textbox", {name: "Name"});
      await nameField.fill("derived-shadow");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {name: "Reference"});
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

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {name: "Reference"});
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
      const shadowSection = workspacePage.rightSidebar.getByTestId("shadow-section");
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

      nameField = tokensUpdateCreateModal.getByRole("textbox", {name: "Name"});
      await nameField.fill("body-text");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {name: "Reference"})
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

      nameField = tokensUpdateCreateModal.getByRole("textbox", {name: "Name"});
      await nameField.fill("paragraph-style");

      const referenceToggle =
        tokensUpdateCreateModal.getByTestId("reference-opt");
      await referenceToggle.click();

      const referenceField = tokensUpdateCreateModal.getByRole("textbox", {name: "Reference"});
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
