import { test, expect } from "@playwright/test";
import { readFile } from "node:fs/promises";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

// ---------------------------------------------------------------------------
// BUG 10925 - Font family typography asset must not persist across files in
// newly created text layers.
//
// `save-font` writes the current font (plus the typography refs of the edited
// shape, when it uses one) into the session-global `:workspace-global
// :default-font`. That state is what seeds the content of brand-new text
// shapes via `v2-default-text-content`. Because it is session-global it
// survives a file switch, so a text created in file B could end up referencing
// a typography asset that only exists in file A (see workspace/texts.cljs
// save-font and workspace.cljs initialize/finalize-workspace).
//
// This E2E reproduces the leak faithfully in a single SPA session:
//   1. Open file A (has a text shape linked to a typography asset).
//   2. Change a font attribute on that shape (triggers `emit-update!` ->
//      `save-font` with the current text-node attrs, typography refs included).
//   3. Switch to file B (same session, fragment navigation keeps JS state).
//   4. Create a brand-new text layer in file B.
//   5. Assert the new text uses the DEFAULT Penpot font ("Source Sans Pro"),
//      not the typography font-family carried over from file A.
// ---------------------------------------------------------------------------

const FILE_A = {
  id: "1062e0a0-8fe0-80ae-8007-e70b4993f5ef",
  pageId: "1062e0a0-8fe0-80ae-8007-e70b4993f5f0",
  // "Text with typography asset one" carries a ref to in-file typography whose
  // font-family is "IM Fell French Canon SC" (multiselection-typography.json).
};

const FILE_B = {
  id: "434b0541-fa2f-802f-8006-59827d964a9b",
  pageId: "434b0541-fa2f-802f-8006-59827d964a9c",
  // render-wasm/get-file-text-custom-fonts.json - a mostly empty file whose
  // only text uses the default font (no typography asset).
};

async function serveTwoFiles(page) {
  const fileABody = await readFile(
    "playwright/data/workspace/multiselection-typography.json",
    "utf-8",
  );
  const fileBBody = await readFile(
    "playwright/data/render-wasm/get-file-text-custom-fonts.json",
    "utf-8",
  );

  // Dispatch on the `id` query param of the `get-file` RPC so each file gets
  // its own fixture while keeping a single SPA session alive.
  await page.route(/get\-file\?/, (route) => {
    const url = new URL(route.request().url());
    const fileId = url.searchParams.get("id");
    const body = fileId === FILE_A.id ? fileABody : fileBBody;
    return route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      body,
    });
  });
}

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  // WASM_FLAGS already enables the v2 text editor / render-wasm. Add the WASM
  // text editor on top so typography styles are read through the current text
  // values path.
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-text-editor-wasm"]);
});

test("BUG 10925 - typography font does not leak into new text in a different file", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page, { textEditor: true });
  await workspace.setupEmptyFile();
  await workspace.mockRPC(
    "get-font-variants?team-id=*",
    "render-wasm/get-font-variants-custom-fonts.json",
  );

  await serveTwoFiles(page);

  // ---- File A: select the text linked to a typography and change a font ----
  await workspace.goToWorkspace({ fileId: FILE_A.id, pageId: FILE_A.pageId });
  await workspace.waitForFirstRender();
  await workspace.doubleClickLeafLayer("Text with typography asset one");
  await workspace.textEditor.startEditing();

  // Changing a font attribute triggers save-font with the current text-node
  // attrs (including the typography refs) storing them into default-font.
  await workspace.textEditor.changeFontSize(24);
  await workspace.textEditor.stopEditing();

  // ---- File B: same SPA session, switch to a file with no typography ----
  await workspace.goToWorkspace({ fileId: FILE_B.id, pageId: FILE_B.pageId });
  await workspace.waitForFirstRender();

  // Create a brand-new text layer in file B and query its font-family.
  await workspace.createTextShape(100, 100, 300, 200, "hello");
  await workspace.textEditor.stopEditing();
  await workspace.clickLeafLayer("hello");
  await workspace.textEditor.startEditing();
  await workspace.page.keyboard.press("ControlOrMeta+a");

  const fontFamily = workspace.rightSidebar.getByTitle("Font Family");
  await expect(fontFamily).toContainText("Source Sans Pro");
  // The custom typography family from file A (IM Fell French Canon SC) must NOT
  // be carried over.
  await expect(fontFamily).not.toContainText("IM Fell");
});