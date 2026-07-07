import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

const FILE = {
  id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
  pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
};

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  // WASM_FLAGS already enables render-wasm; add the WASM text editor on top.
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-text-editor-wasm"]);
});

async function openEditorAndSelectAll(workspace) {
  await workspace.clickLeafLayer("this is a text");
  // Enter edit mode (waits until the typography controls are ready) and then
  // select every character so the sidebar reflects the combined styles of the
  // whole text via the WASM editor path.
  await workspace.textEditor.startEditing();
  await workspace.page.keyboard.press("ControlOrMeta+a");
}

test.describe("BUG 10502 - Mixed families and variants", () => {
  test("Multiple variants of the same font family", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("text-editor/get-file-10502-mixed-variants.json");

    await workspace.goToWorkspace(FILE);
    await workspace.waitForFirstRender();

    await openEditorAndSelectAll(workspace);

    // The whole selection shares a single font family, so it must be shown even
    // though the variants differ.
    const fontFamily = workspace.rightSidebar.getByTitle("Font Family");
    await expect(fontFamily).toContainText("Source Sans Pro");

    // The variants differ across the selection, so the variant dropdown shows the
    // "mixed" placeholder.
    const fontVariant = workspace.rightSidebar
      .getByTitle("Font Style")
      .getByRole("combobox");
    await expect(fontVariant).toHaveText("--");
  });

  test("Mixed font families appear as such in the dropdown", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("text-editor/get-file-10502-mixed-families.json");
    // Serve a stand-in TTF for Sora so the render doesn't wait on a real fetch.
    // Glyphs are irrelevant here: the assertion only inspects the sidebar.
    await workspace.mockGoogleFont("sora", "render-wasm/assets/ebgaramond.ttf");

    await workspace.goToWorkspace(FILE);
    await workspace.waitForFirstRender();

    await openEditorAndSelectAll(workspace);

    // The selection mixes two different font families (Source Sans Pro and Sora),
    // so the font family dropdown reports it as mixed.
    const fontFamily = workspace.rightSidebar.getByTitle("Font Family");
    await expect(fontFamily).toContainText("Mixed Font Families");
  });
});

test.describe("BUG 10530 - Empty text box left behind when leaving the editor", () => {
  test("An empty text box is removed when leaving the editor by clicking outside", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const layerRows = workspace.layers.getByTestId("layer-row");
    await expect(layerRows).toHaveCount(0);

    // Draw an empty text box
    await workspace.createTextShape(200, 150, 320, 210);
    // The shape exists while it is being edited
    await expect(layerRows).toHaveCount(1);

    // Leave the editor by clicking outside
    await workspace.clickAt(500, 400);

    await expect(layerRows).toHaveCount(0);
  });

  test("A non-empty text box is kept when leaving the editor by clicking outside", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const layerRows = workspace.layers.getByTestId("layer-row");

    // A text box with content must survive leaving the editor by clicking outside.
    await workspace.createTextShape(200, 150, 320, 210, "hello");
    await workspace.clickAt(500, 400);

    await expect(layerRows).toHaveCount(1);
  });
});

test("BUG 10467 - Auto-width text captures every typed character", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, { textEditor: true });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  const layerRows = workspace.layers.getByTestId("layer-row");

  // A single click with the text tool creates an auto-width text box by default
  await workspace.createAutoWidthTextShape(200, 150, "hello world");

  // Leave the editor to finalize the content
  await workspace.textEditor.stopEditing();

  // Assert the whole typed text made it into the shape
  await workspace.layers.getByTestId("layer-row").first().click();
  await workspace.waitForSelectedShapeName("hello world");
});

