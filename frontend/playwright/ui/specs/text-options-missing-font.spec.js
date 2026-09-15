import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// ---------------------------------------------------------------------------
// The "Create typography style" button (workspace.options.convert-to-typography)
// in the text options sidebar is only shown when ALL of these hold for the
// selected text shape(s) (src/app/main/ui/workspace/sidebar/options/menus/text.cljs):
//   (and (some? font) (not typography) (not multiple?) (not applied-token-name))
// Each test below isolates one condition that must independently hide it:
//   - font missing (font-id not registered in app.main.fonts/fontsdb)
//   - a typography asset is applied (typography-ref-id set)
//   - multiple shapes are selected with differing attributes
//   - a typography design token is applied (applied-tokens :typography)
// ---------------------------------------------------------------------------

function convertToTypographyButton(workspace) {
  return workspace.rightSidebar.getByRole("button", {
    name: "Create typography style",
  });
}

test.describe("font missing", () => {
  // Fixture render-wasm/get-file-text-custom-fonts.json has a text shape
  // ("Penpot & Dragons") using a custom team font-id and no typography/token
  // applied - otherwise exactly the state that reveals the button once its
  // font resolves. Toggling the get-font-variants mock between "the team owns
  // this font" and "empty" simulates the font being present vs. missing.
  const FILE = {
    id: "434b0541-fa2f-802f-8006-59827d964a9b",
    pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
  };

  test.beforeEach(async ({ page }) => {
    await WorkspacePage.init(page);
  });

  test("Create typography style button is hidden when the shape font is missing", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "render-wasm/get-file-text-custom-fonts.json",
    );
    // The team does not own the shape's custom font, so it can't be resolved.
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Penpot & Dragons");

    await expect(convertToTypographyButton(workspace)).not.toBeVisible();
  });

  test("Create typography style button is visible once the shape font resolves", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "render-wasm/get-file-text-custom-fonts.json",
    );
    // The team owns the shape's custom font, so it resolves normally.
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "render-wasm/get-font-variants-custom-fonts.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Penpot & Dragons");

    await expect(convertToTypographyButton(workspace)).toBeVisible();
  });
});

test.describe("typography asset applied", () => {
  // multiselection-typography.json: "Text with typography asset one" has a
  // typography-ref-id pointing at an in-file typography asset (font
  // gfont-agdasima, a built-in Google font that resolves with no extra
  // mocking), and is not multi-selected or token-applied.
  const FILE = {
    id: "1062e0a0-8fe0-80ae-8007-e70b4993f5ef",
    pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
  };

  test.beforeEach(async ({ page }) => {
    await WorkspacePage.init(page);
  });

  test("Create typography style button is hidden when a typography asset is applied", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/multiselection-typography.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Text with typography asset one");

    // Sanity check: the text options panel did render for this shape - the
    // button is specifically hidden by the applied typography, not because
    // the whole panel failed to show up.
    await expect(
      workspace.rightSidebar.getByRole("region", { name: "Text section" }),
    ).toBeVisible();
    await expect(convertToTypographyButton(workspace)).not.toBeVisible();
  });
});

test.describe("multiple selection", () => {
  // get-file-text-multiple-selection.json has two text shapes sharing the
  // same (resolvable, built-in) font-id but differing font-size, with no
  // typography or token applied - so selecting both together isolates
  // `multiple?` becoming true without also making the font unresolved.
  const FILE = {
    id: "434b0541-fa2f-802f-8006-6a827d964a9b",
    pageId: "434b0541-fa2f-802f-8006-6a827d964a9c",
  };

  test.beforeEach(async ({ page }) => {
    await WorkspacePage.init(page);
  });

  test("Create typography style button is hidden when multiple shapes with different values are selected", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-text-multiple-selection.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Text multiple selection one");
    await expect(convertToTypographyButton(workspace)).toBeVisible();

    await workspace.clickLeafLayer("Text multiple selection two", {
      modifiers: ["Shift"],
    });

    await expect(convertToTypographyButton(workspace)).not.toBeVisible();
  });
});

test.describe("typography token applied", () => {
  // get-file-token-tooltip.json: "Text with token" has a typography design
  // token applied (applied-tokens :typography) using font gfont-arizonia (a
  // built-in Google font that resolves with no extra mocking).
  test.beforeEach(async ({ page }) => {
    await WasmWorkspacePage.init(page);
    await WasmWorkspacePage.mockRPC(page, "get-teams", "get-teams-tokens.json");
  });

  test("Create typography style button is hidden when a typography token is applied", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page);
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockRPC("get-team?id=*", "workspace/get-team-tokens.json");
    await workspace.mockRPC(
      /get\-file\?/,
      "workspace/get-file-token-tooltip.json",
    );
    await workspace.mockRPC(
      /get\-file\-fragment\?/,
      "workspace/get-file-fragment-tokens.json",
    );
    await workspace.mockRPC(
      "update-file?id=*",
      "workspace/update-file-create-rect.json",
    );
    await workspace.goToWorkspace({
      fileId: "c7ce0794-0992-8105-8004-38f280443849",
      pageId: "4530574a-7a0a-807b-8008-0107b2c4628e",
    });

    await page.getByRole("tab", { name: "Layers" }).click();
    await workspace.layers
      .getByTestId("layer-row")
      .filter({ hasText: "Text with token" })
      .click();

    // Sanity check: the text options panel did render for this shape - the
    // button is specifically hidden by the applied token, not because the
    // whole panel failed to show up.
    await expect(
      workspace.rightSidebar.getByRole("region", { name: "Text section" }),
    ).toBeVisible();
    await expect(convertToTypographyButton(workspace)).not.toBeVisible();
  });
});
