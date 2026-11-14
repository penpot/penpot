import { test, expect } from "@playwright/test";
import { WorkspacePage } from "../pages/WorkspacePage";

// ---------------------------------------------------------------------------
// The "Add typography" button in the local library typographies section
// (src/app/main/ui/workspace/sidebar/assets/typographies.cljs) creates a
// typography from the selected text. Its aria-label changes and the button is
// disabled when:
//   - the selected text uses a font that is not installed
//   - two or more texts are selected
// ---------------------------------------------------------------------------

function addTypographyButton(workspace, name) {
  return workspace.sidebar.getByRole("button", {
    name,
    exact: typeof name === "string",
  });
}

// The text shape uses a custom team font-id, and the get-font-variants mock
// decides whether that font is installed or missing.
const CUSTOM_FONTS_FILE = {
  id: "434b0541-fa2f-802f-8006-59827d964a9b",
  pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
};

// Two text shapes that share the same built-in font, so only the selection
// count changes between the two states.
const MULTIPLE_SELECTION_FILE = {
  id: "434b0541-fa2f-802f-8006-6a827d964a9b",
  pageId: "434b0541-fa2f-802f-8006-6a827d964a9c",
};

test("The font of the selected text decides the button", async ({ page }) => {
  await WorkspacePage.init(page);

  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "render-wasm/get-file-text-custom-fonts.json",
  );

  await test.step("font installed", async () => {
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "render-wasm/get-font-variants-custom-fonts.json",
    );
    await workspace.goToWorkspace({
      fileId: CUSTOM_FONTS_FILE.id,
      pageId: CUSTOM_FONTS_FILE.pageId,
    });

    await workspace.clickLeafLayer("Penpot & Dragons");
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, "Add typography"),
    ).toBeEnabled();
  });

  await test.step("font missing", async () => {
    // The last matching route wins, and the reload fetches the variants again.
    await workspace.mockRPC(
      "get-font-variants?team-id=*",
      "workspace/get-font-variants-empty.json",
    );
    await page.reload();

    // The sidebar keeps the Assets tab across the reload, so it has to go
    // back to Layers before the tree rows exist.
    await workspace.clickLayers();
    await workspace.clickLeafLayer("Penpot & Dragons");
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, /is no longer available/),
    ).toBeDisabled();
  });
});

test("The number of selected texts decides the button", async ({ page }) => {
  await WorkspacePage.init(page);

  const workspace = new WorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    /get\-file\?/,
    "workspace/get-file-text-multiple-selection.json",
  );
  await workspace.goToWorkspace({
    fileId: MULTIPLE_SELECTION_FILE.id,
    pageId: MULTIPLE_SELECTION_FILE.pageId,
  });

  await test.step("one text", async () => {
    await workspace.clickLeafLayer("Text multiple selection one");
    await workspace.clickAssets();

    await expect(
      addTypographyButton(workspace, "Add typography"),
    ).toBeEnabled();
  });

  await test.step("two texts", async () => {
    await workspace.clickLayers();
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
});
