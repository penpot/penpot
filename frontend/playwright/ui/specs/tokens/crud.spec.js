import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { BaseWebSocketPage } from "../../pages/BaseWebSocketPage";
import {
  setupEmptyTokensFile,
  setupTokensFile,
  setupTypographyTokensFile,
  testTokenCreationFlow,
  unfoldTokenTree,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
  await BaseWebSocketPage.mockRPC(page, "get-teams", "get-teams-tokens.json");
});

test.describe("Tokens - creation", () => {
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

  test("User creates shadow token with negative spread", async ({ page }) => {
    const emptyNameError = "Name should be at least 1 character";

    const { tokensUpdateCreateModal, tokenThemesSetsSidebar } =
          await setupEmptyTokensFile(page, {flags: ["enable-token-shadow"]});

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

    await spreadField.fill("3 - 3");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: 0"),
    ).toBeVisible();

    await spreadField.fill("1 - 3");
    await expect(
      tokensUpdateCreateModal.getByText("Resolved value: -2"),
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
        "Resolved value: - X: 6 - Y: 10 - Blur: 4 - Spread: -2",
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

    const referenceTabButton = tokensUpdateCreateModal.getByRole("button", {
      name: "Use a reference",
    });
    referenceTabButton.click();

    const referenceField = tokensUpdateCreateModal.getByRole("textbox", {
      name: "Reference",
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

test.describe("Tokens tab - edition", () => {
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

  test("User edits color token color while keeping custom color space", async ({
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
});

test.describe("Tokens tab - delete", () => {
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

  test("User removes node and all child tokens", async ({ page }) => {
    const { tokensSidebar, workspacePage } = await setupTokensFile(page);

    await expect(tokensSidebar).toBeVisible();

    // Expand color tokens
    unfoldTokenTree(tokensSidebar, "color", "colors.blue.100");

    // Verify that the node and child token are visible before deletion
    const colorNode = tokensSidebar.getByRole("button", {
      name: "blue",
      exact: true,
    });
    const colorNodeToken = tokensSidebar.getByRole("button", {
      name: "100",
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
  });
});
