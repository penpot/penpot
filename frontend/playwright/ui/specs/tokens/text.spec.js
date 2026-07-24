import { test, expect } from "@playwright/test";
import {
  unfoldTokenType,
} from "./helpers";
import { WasmWorkspacePage } from "../../pages/WasmWorkspacePage";

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
});

test("BUG 13958 - Fill token gets detached when editing text shape", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  // Load a file that has a text shape, whose content contains a `:fills []` in the root node.
  // This attribute is removed when editing the text, causing the old code to detect that the
  // fills had changed and detaching the token.
  await workspacePage.mockGetFile("workspace/get-file-13958.json");
  await workspacePage.goToWorkspace();

  // Check token is attached to the shape
  await workspacePage.clickLeafLayer("Design tokens are a set");
  await expect(workspacePage.rightSidebar.getByLabel("xx.alias.color.text.default", { exact: true })).toBeVisible();

  // Enter and exit the text editor
  await workspacePage.page.keyboard.press("Enter");
  await expect(workspacePage.page.getByTestId("text-editor")).toBeVisible();
  await workspacePage.page.keyboard.press("Escape");
  await expect(workspacePage.page.getByTestId("text-editor")).not.toBeAttached();

  // Assert token is still attached to the shape
  await expect(workspacePage.rightSidebar.getByLabel("xx.alias.color.text.default", { exact: true })).toBeVisible();
});

test("Selection change clears text-edition mode so tokens can be applied", async ({ page }) => {
  const workspacePage = new WasmWorkspacePage(page);
  await workspacePage.setupEmptyFile();
  await workspacePage.mockGetFile("workspace/get-file-13958.json");
  await workspacePage.goToWorkspace();
  await workspacePage.rectShapeButton.click();
  await workspacePage.clickWithDragViewportAt(128, 128, 200, 100);
  await workspacePage.clickLeafLayer("Rectangle");

  // Enter text editing on the text layer
  await workspacePage.clickLeafLayer("Design tokens are a set");
  await workspacePage.page.keyboard.press("Enter");
  await expect(workspacePage.page.getByTestId("text-editor")).toBeVisible();

  // Shift-click another layer to change selection while editing
  await workspacePage.layers
    .getByTestId("layer-row")
    .filter({ hasText: "Rectangle" })
    .click({ modifiers: ["Shift"] });

  // Text editor should close because selection change emits :interrupt
  await expect(workspacePage.page.getByTestId("text-editor")).not.toBeAttached();

  // Open tokens tab and try to apply a fill token — should succeed without warning
  await page.getByRole("tab", { name: "Tokens" }).click();

  const tokensSidebar = page.getByTestId("tokens-sidebar");

  await unfoldTokenType(tokensSidebar, "color");

  // Right-click a color token and apply as fill
  await tokensSidebar
    .getByRole('button', { name: '#934846 xx.global.color.red.' })
    .click({ button: "right" });
  await workspacePage.tokenContextMenuForToken.getByText("Fill").click();

  // Verify no warning toast appeared about text editing
  await expect(
    page.getByRole("alert").filter({
      hasText: /Tokens can't be applied while editing text/i,
    }),
  ).not.toBeVisible();
});
