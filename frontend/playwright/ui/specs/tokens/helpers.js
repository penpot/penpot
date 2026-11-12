import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";

/**
 * Sets up the workspace with a file that contains stroke width and dimensions
 * tokens, and the per-side stroke feature flag enabled.
 *
 * @param {import("@playwright/test").Page} page
 * @param {{flags?: string[]}} [options]
 */
const setupStrokePerSideFile = async (page, options = {}) => {
  const { flags = [] } = options;

  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.mockConfigFlags([...flags, "enable-stroke-per-side"]);

  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile(
    "workspace/get-file-layout-stroke-token-json",
  );

  await workspacePage.goToWorkspace();
  await workspacePage.waitForFirstRender();

  return workspacePage;
};

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

const setupEmptyTokensFileRender = async (page, options = {}) => {
  const { flags = [] } = options;

  const workspacePage = new WasmWorkspacePage(page);
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
    tokenContextMenuForToken: workspacePage.tokenContextMenuForToken,
    tokenContextMenuForSet: workspacePage.tokenContextMenuForSet,
  };
};

const setupTokensFile = async (page, options = {}) => {
  const {
    file = "workspace/get-file-tokens.json",
    fileFragment = "workspace/get-file-fragment-tokens.json",
    flags = ["enable-feature-token-input"],
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

const setupTokensFileRender = async (page, options = {}) => {
  const {
    file = "workspace/get-file-tokens.json",
    fileFragment = "workspace/get-file-fragment-tokens.json",
    flags = ["enable-feature-token-input"],
  } = options;

  const workspacePage = new WasmWorkspacePage(page);
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
    tokensRenameNodeModal: workspacePage.tokensRenameNodeModal,
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

const setupTypographyTokensFileRender = async (page, options = {}) => {
  return setupTokensFileRender(page, {
    file: "workspace/get-file-typography-tokens.json",
    fileFragment: "workspace/get-file-fragment-typography-tokens.json",
    ...options,
  });
};

const tokenValuePlaceholder = "Enter a value or alias with {alias}";

const openTokenCreationForm = async (
  page,
  tokensUpdateCreateModal,
  { tokenLabel, namePlaceholder },
) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  await tokensTabPanel
    .getByRole("button", { name: `Add Token: ${tokenLabel}` })
    .click();

  await expect(tokensUpdateCreateModal).toBeVisible();

  await expect(
    tokensUpdateCreateModal.getByPlaceholder(namePlaceholder),
  ).toBeVisible();
  await expect(
    tokensUpdateCreateModal.getByPlaceholder(tokenValuePlaceholder),
  ).toBeVisible();
};

const assertTokenCreationLadder = async (
  tokensUpdateCreateModal,
  { name, invalidValue = "red" },
) => {
  const nameField = tokensUpdateCreateModal.getByLabel("Name");
  const valueField = tokensUpdateCreateModal.getByLabel("Value");
  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });

  // 1. Name filled + empty value -> disabled
  await nameField.fill(name);
  await expect(submitButton).toBeDisabled();

  // 2. Invalid value -> disabled + error message
  await valueField.fill(invalidValue);
  await expect(
    tokensUpdateCreateModal.getByText("Invalid token value"),
  ).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 3. Empty name -> disabled + error message
  await nameField.fill("");
  await expect(
    tokensUpdateCreateModal.getByText("Name should be at least 1 character"),
  ).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 4. Self reference -> disabled + error message
  await nameField.fill(name);
  await valueField.fill(`{${name}}`);
  await expect(
    tokensUpdateCreateModal.getByText("Token has self reference"),
  ).toBeVisible();
  await expect(submitButton).toBeDisabled();

  // 5. Missing reference -> disabled + error message
  await valueField.fill("{missing-token}");
  await expect(
    tokensUpdateCreateModal.getByText("Missing token references"),
  ).toBeVisible();
  await expect(submitButton).toBeDisabled();
};

const saveToken = async (
  page,
  tokensUpdateCreateModal,
  { tokenLabel, name, value, resolvedText },
) => {
  const tokensTabPanel = page.getByRole("tabpanel", { name: "tokens" });

  await tokensUpdateCreateModal.getByLabel("Name").fill(name);
  await tokensUpdateCreateModal.getByLabel("Value").fill(value);

  await expect(tokensUpdateCreateModal.getByText(resolvedText)).toBeVisible();

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await expect(submitButton).toBeEnabled();
  await submitButton.click();

  await unfoldTokenType(tokensTabPanel, tokenLabel.toLocaleLowerCase());

  await expect(tokensTabPanel.getByRole("button", { name })).toBeEnabled();
};

const createTokenWithValue = async (
  page,
  tokensUpdateCreateModal,
  {
    tokenLabel,
    namePlaceholder,
    name,
    value = "2 + 3",
    resolvedText = "Resolved value: 5",
  },
) => {
  await openTokenCreationForm(page, tokensUpdateCreateModal, {
    tokenLabel,
    namePlaceholder,
  });

  await saveToken(page, tokensUpdateCreateModal, {
    tokenLabel,
    name,
    value,
    resolvedText,
  });
};

const unfoldTokenType = async (tokensTabPanel, type) => {
  const kebabClaseType = type.toLocaleLowerCase().replace(/\s/g, "-");
  const typeParentWrapper = tokensTabPanel.getByTestId(
    `section-${kebabClaseType}`,
  );
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
};

/**
 * Creates a token from the Tokens sidebar modal.
 *
 * @param {import("@playwright/test").Page} page - Playwright page instance.
 * @param {string} type - Token category label shown in UI (e.g. "Color", "Typography", "Shadow").
 * @param {string} name - Token name to create.
 * @param {string} textFieldName - Accessible label of the value textbox in the modal (e.g. "Value", "Color", "Font size").
 * @param {"textbox" | "combobox"} textFieldType - Type of the token value field, wether it's a simple text field or a combo box, to properly fill the value.
 * @param {string} value - Token value to set in the modal.
 * @returns {Promise<void>}
 */

const createToken = async (
  page,
  type,
  name,
  textFieldName,
  textFieldType,
  value,
) => {
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

  const valueField = tokensUpdateCreateModal.getByRole(textFieldType, {
    name: textFieldName,
  });
  await valueField.fill(value);

  const submitButton = tokensUpdateCreateModal.getByRole("button", {
    name: "Save",
  });
  await submitButton.click();
  await expect(tokensUpdateCreateModal).not.toBeVisible();
};

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

export {
  setupEmptyTokensFile,
  setupEmptyTokensFileRender,
  setupStrokePerSideFile,
  setupTokensFile,
  setupTokensFileRender,
  setupTypographyTokensFile,
  setupTypographyTokensFileRender,
  openTokenCreationForm,
  assertTokenCreationLadder,
  saveToken,
  createTokenWithValue,
  unfoldTokenType,
  createToken,
  createSet,
  changeSetInput,
};
