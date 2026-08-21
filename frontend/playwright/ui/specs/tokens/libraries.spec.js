import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";
import {
  setupEmptyTokensFileRender,
  createToken,
  unfoldTokenType,
} from "./helpers";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("File with tokens is its own tokens source", async ({ page }) => {
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-token-lib-sync"]);
  const workspacePage = new WasmWorkspacePage(page);

  await workspacePage.setupEmptyFile(page);

  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-libraries-non-empty.json",
  );

  await workspacePage.goToWorkspace();

  // A file without tokens is not tokens source
  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal
    .getByRole("tab", { name: "This file" })
    .click();

  await expect(
    workspacePage.librariesModal.getByText("Tokens source"),
  ).not.toBeVisible();

  await workspacePage.closeLibrariesModal();

  // Create a token in the file
  await workspacePage.clickTokens();
  await createToken(
    page,
    "Color",
    "color.primary",
    "Value",
    "textbox",
    "#ff0000",
  );

  // Now it is a tokens source
  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal
    .getByRole("tab", { name: "This file" })
    .click();

  await expect(
    workspacePage.librariesModal.getByText("Tokens source"),
  ).toBeVisible();
});

test("User sets a library as tokens source and then they can use the tokens in it", async ({
  page,
}) => {
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-token-lib-sync"]);

  // Set up a file linked to a library with tokens

  const { workspacePage, tokenThemesSetsSidebar, tokenContextMenuForSet } =
    await setupEmptyTokensFileRender(page);

  await workspacePage.mockGetFile("workspace/get-file-with-lib.json");

  await workspacePage.mockRPC(
    "get-file-libraries?file-id=*",
    "workspace/get-file-libraries-tokens.json",
  );

  await workspacePage.mockRPC(
    /get\-file\?id=c7ce0794-0992-8105-8004-38f280443849/,
    "workspace/get-file-tokens.json",
  );

  await workspacePage.mockRPC(
    "get-team-shared-files?team-id=*",
    "workspace/get-team-shared-libraries-non-empty.json",
  );

  await workspacePage.goToWorkspace();

  // Set the external library as the tokens source

  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal
    .getByRole("tab", { name: "This file" })
    .click();
  await workspacePage.librariesModal
    .getByRole("button", { name: "Set as tokens source" })
    .click();
  await workspacePage.closeLibrariesModal();

  // Check that we can apply a token from the library to a shape

  await workspacePage.sidebar.getByRole("tab", { name: "Tokens" }).click();
  await page.waitForTimeout(500);

  // Select the first shape in the file
  await page.keyboard.press("v");
  await workspacePage.clickAt(200, 150);

  // Apply a color token from the library as the shape fill
  await workspacePage.sidebar.getByRole("tab", { name: "Tokens" }).click();
  await unfoldTokenType(workspacePage.tokensSidebar, "Color");

  const colorTokenName = "colors.red.600";
  await workspacePage.tokensSidebar
    .getByRole("button", { name: colorTokenName })
    .click({ button: "right" });
  await workspacePage.tokenContextMenuForToken.getByText("Fill").click();

  // Check that the token has been correctly applied in the right sidebar
  const fillSection = workspacePage.rightSidebar.getByRole("region", {
    name: "Fill section",
  });
  await expect(fillSection).toBeVisible();

  const fillTokenPill = fillSection.getByLabel(colorTokenName, {
    exact: true,
  });
  await expect(fillTokenPill).toBeVisible();

  // Go back to the libraries modal and set the current file as tokens source
  await workspacePage.clickAssets();
  await workspacePage.openLibrariesModal();
  await workspacePage.librariesModal
    .getByRole("tab", { name: "This file" })
    .click();
  await workspacePage.librariesModal
    .getByRole("button", { name: "Set as tokens source" })
    .click();
  await workspacePage.closeLibrariesModal();

  // Check that the applied token now looks broken
  await expect(fillSection).toBeVisible();
  const brokenTokenPill = fillSection.getByLabel(colorTokenName, {
    exact: true,
  });
  await expect(brokenTokenPill).toBeVisible();
  await brokenTokenPill.hover();

  const brokenTokenTooltip = page.getByRole("tooltip", {
    name: colorTokenName,
  });
  await expect(brokenTokenTooltip).toBeVisible();
  await expect(brokenTokenTooltip).toHaveText(
    `{${colorTokenName}} token does not exist or has been deleted.`,
  );
});
