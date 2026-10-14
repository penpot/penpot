import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// ---------------------------------------------------------------------------
// The "Add typography" button in the local library typographies section
// (src/app/main/ui/workspace/sidebar/assets/typographies.cljs) creates a
// typography from the selected text. It is disabled, and its label explains
// why, when:
//   - the selected text uses a font that is not installed
//   - two or more texts are selected
// ---------------------------------------------------------------------------

function addTypographyButton(workspace, name) {
  return workspace.sidebar.getByRole("button", {
    name,
    exact: typeof name === "string",
  });
}

test.beforeEach(async ({ page }) => {
  await WorkspacePage.init(page);
});

test.describe("font of the selected text", () => {
  // render-wasm/get-file-text-custom-fonts.json has a text shape
  // ("Penpot & Dragons") using a custom team font-id. The get-font-variants
  // mock decides whether that font is installed or missing.
  const FILE = {
    id: "434b0541-fa2f-802f-8006-59827d964a9b",
    pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
  };

  test("Add typography is enabled when the selected text font is installed", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "render-wasm/get-file-text-custom-fonts.json",
    );
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "render-wasm/get-font-variants-custom-fonts.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Penpot & Dragons");
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, "Add typography"),
    ).toBeEnabled();
  });

  test("Add typography is disabled when the selected text font is missing", async ({
    page,
  }) => {
    const workspace = new WorkspacePage(page);
    await workspace.setupEmptyFile();
    await workspace.mockRPC(
      /get\-file\?/,
      "render-wasm/get-file-text-custom-fonts.json",
    );
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );
    await workspace.goToWorkspace({ fileId: FILE.id, pageId: FILE.pageId });

    await workspace.clickLeafLayer("Penpot & Dragons");
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, /is no longer available/),
    ).toBeDisabled();
  });
});

test.describe("number of selected texts", () => {
  // workspace/get-file-text-multiple-selection.json has two text shapes that
  // use the same built-in font, so only the selection count changes.
  const FILE = {
    id: "434b0541-fa2f-802f-8006-6a827d964a9b",
    pageId: "434b0541-fa2f-802f-8006-6a827d964a9c",
  };

  test("Add typography is disabled when two texts are selected", async ({
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
    await workspace.clickLeafLayer("Text multiple selection two", {
      modifiers: ["Shift"],
    });
    await workspace.clickAssets();

    await expect(
      addTypographyButton(
        workspace,
        "Select one text to create a typography style",
      ),
    ).toBeDisabled();
  });

  test("Add typography is enabled when one text is selected", async ({
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
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, "Add typography"),
    ).toBeEnabled();
  });
});
