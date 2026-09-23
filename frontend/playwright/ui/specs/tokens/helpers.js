import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { WorkspacePage } from "../../pages/WorkspacePage";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";

// Minimal transit helpers, matching ui/pages/ShortcutsPage.js. Used to keep a
// profile mock that reflects `update-profile-props` calls, so the persisted
// `stroke-per-side` preference survives the profile refresh that the app
// triggers after every props update.
function decodeKeyword(value) {
  if (typeof value === "string" && value.startsWith("~:")) {
    return value.slice(2);
  }
  if (typeof value === "string" && value.startsWith("~$")) {
    return value.slice(2);
  }
  return value;
}

function decodeTransit(data) {
  if (Array.isArray(data)) {
    if (data[0] === "^ ") {
      const result = {};
      for (let i = 1; i < data.length; i += 2) {
        result[decodeTransit(data[i])] = decodeTransit(data[i + 1]);
      }
      return result;
    }
    return data.map(decodeTransit);
  }
  if (data !== null && typeof data === "object") {
    const result = {};
    for (const [key, value] of Object.entries(data)) {
      result[decodeKeyword(key)] = decodeTransit(value);
    }
    return result;
  }
  return decodeKeyword(data);
}

function decodeRequestBody(body) {
  try {
    return decodeTransit(JSON.parse(body));
  } catch {
    return null;
  }
}

/**
 * Sets up the workspace with a file that contains stroke width and dimensions
 * tokens, plus a persistent user profile, so the per-side stroke preference
 * (`stroke-per-side`) round-trips through update-profile-props/get-profile
 * like it does against a real backend.
 *
 * @param {import("@playwright/test").Page} page
 * @param {{flags?: string[], profileProps?: object}} [options]
 */
const setupStrokePerSideFile = async (page, options = {}) => {
  const { flags = [], profileProps = {} } = options;

  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.mockConfigFlags([...flags, "enable-stroke-per-side"]);

  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile(
    "workspace/get-file-layout-stroke-token-json",
  );

  const profileText = await readFile(
    "playwright/data/logged-in-user/get-profile-wasm-renderer.json",
    "utf-8",
  );
  // Work on the raw transit JSON so keyword values such as `~:renderer`
  // `~:wasm` keep their type; round-tripping through a generic decoder would
  // turn them into plain strings and the app would fall back to the SVG
  // renderer.
  const profile = JSON.parse(profileText);
  profile["~:props"] = profile["~:props"] ?? {};

  for (const [key, value] of Object.entries(profileProps)) {
    profile["~:props"][`~:${key}`] = value;
  }

  await page.route("**/api/main/methods/update-profile-props", (route) => {
    const decoded = decodeRequestBody(route.request().postData() ?? "{}");
    if (decoded?.props) {
      for (const [key, value] of Object.entries(decoded.props)) {
        profile["~:props"][`~:${key}`] = value;
      }
    }
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      body: "{}",
    });
  });

  await page.route("**/api/main/methods/get-profile", (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      body: JSON.stringify(profile),
    });
  });

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

  const { tokensUpdateCreateModal, tokensSidebar } =
    await setupEmptyTokensFileRender(page);

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
  testTokenCreationFlow,
  unfoldTokenType,
  createToken,
  createSet,
  changeSetInput,
};
