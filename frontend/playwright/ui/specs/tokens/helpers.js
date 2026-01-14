import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../../pages/WorkspacePage";

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

const setupTypographyTokensFile = async (page, options = {}) => {
  return setupTokensFile(page, {
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

export {
  setupEmptyTokensFile,
  setupTokensFile,
  setupTypographyTokensFile,
  testTokenCreationFlow,
  unfoldTokenTree,
};
