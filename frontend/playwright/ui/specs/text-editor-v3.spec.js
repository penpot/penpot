import { test, expect } from "@playwright/test";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

const FILE = {
  id: "3b0d758a-8c9d-8013-8006-52c8337e5c72",
  pageId: "3b0d758a-8c9d-8013-8006-52c8337e5c73",
};

test.beforeEach(async ({ page }) => {
  await WasmWorkspacePage.init(page);
  // WASM_FLAGS already enables render-wasm; add the WASM text editor on top.
  await WasmWorkspacePage.mockConfigFlags(page, [
    "enable-feature-text-editor-wasm",
  ]);
});

async function openEditorAndSelectAll(workspace) {
  await workspace.clickLeafLayer("this is a text");
  // Enter edit mode (waits until the typography controls are ready) and then
  // select every character so the sidebar reflects the combined styles of the
  // whole text via the WASM editor path.
  await workspace.textEditor.startEditing();
  await workspace.page.keyboard.press("ControlOrMeta+a");
}


test("Typography at a collapsed caret only styles newly typed text", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, { textEditor: true });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  const fontSize = workspace.textEditor.fontSize;
  const editorInput = page.getByTestId("text-editor-container");

  // Draw a text box, focus it, and type some text; the caret ends up collapsed
  // after it.
  await workspace.createTextShape(200, 150, 460, 260);
  await workspace.clickAt(210, 160);
  await expect(editorInput).toBeFocused();
  await page.keyboard.type("ab");

  const originalSize = await fontSize.inputValue();
  const newSize = String(Number(originalSize) + 20);

  // Change the font size with a collapsed caret. This must not restyle the
  // existing text; it is stashed as a pending style for the next input. Focus
  // returns to the editor once the sidebar input blurs.
  await workspace.textEditor.changeFontSize(newSize);
  await expect(editorInput).toBeFocused();

  // Typing now adopts the pending size as its own span.
  await page.keyboard.type("X");

  // The just-typed "X" carries the new size...
  await page.keyboard.press("Shift+ArrowLeft");
  await expect(fontSize).toHaveValue(newSize);

  // ...while the pre-existing "ab" keeps the original size (the bug applied the
  // change to the whole shape instead).
  await page.keyboard.press("Home");
  await page.keyboard.press("Shift+ArrowRight");
  await page.keyboard.press("Shift+ArrowRight");
  await expect(fontSize).toHaveValue(originalSize);
});

test.describe("BUG 10502 - Mixed families and variants", () => {
  test("Multiple variants of the same font family", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.mockGetFile(
      "text-editor/get-file-10502-mixed-variants.json",
    );

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

  test("Mixed font families appear as such in the dropdown", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.mockGetFile(
      "text-editor/get-file-10502-mixed-families.json",
    );
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

test.describe("BUG 11083 - Changing typography must not quit the editor", () => {
  test("Changing a numeric input must not quit the editor", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const layerRows = workspace.layers.getByTestId("layer-row");

    // Draw an empty text box and, without typing anything, change the font size.
    await workspace.createTextShape(200, 150, 320, 210);
    await expect(layerRows).toHaveCount(1);

    await workspace.textEditor.changeFontSize(24);

    // The shape is not deleted and the editor is still mounted.
    await expect(layerRows).toHaveCount(1);
    await expect(page.getByTestId("text-editor")).toBeVisible();

    // The edition survives, so we can click back into the box and keep typing.
    await workspace.clickAt(210, 160);
    await page.keyboard.type("hello");
    await workspace.textEditor.stopEditing();

    await layerRows.first().click();
    await workspace.waitForSelectedShapeName("hello");
  });

  test("Opening the font family selector must not quit the editor", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    const layerRows = workspace.layers.getByTestId("layer-row");

    // Draw an empty text box and, without typing anything, open the font family
    // selector
    await workspace.createTextShape(200, 150, 320, 210);
    await expect(layerRows).toHaveCount(1);

    await workspace.rightSidebar.getByTitle("Font Family").click();

    // The shape is not deleted and the editor is still mounted.
    await expect(layerRows).toHaveCount(1);
    await expect(page.getByTestId("text-editor")).toBeVisible();

    // The edition survives, so we can click back into the box and keep typing.
    await workspace.clickAt(210, 160);
    await page.keyboard.type("hello");
    await workspace.textEditor.stopEditing();

    await layerRows.first().click();
    await workspace.waitForSelectedShapeName("hello");
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

test.describe("BUG 10910 - Text is not replaced when there is a selection", () => {
  // Non-ascii on purpose: selection offsets are counted in characters.
  test("Typing over a selection replaces it", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await workspace.createAutoWidthTextShape(200, 150, "Añadir");

    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.type("nuevo");

    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("nuevo");
  });

  test("Typing over a selection that contains emoji replaces it", async ({
    page,
  }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await workspace.createAutoWidthTextShape(200, 150, "Hola 😀");

    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.type("ok");

    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("ok");
  });

  test("Backspace deletes the selection", async ({ page }) => {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await workspace.createAutoWidthTextShape(200, 150, "Añadir texto");

    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.press("Backspace");
    await page.keyboard.type("ok");

    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("ok");
  });
});

test("BUG 10531 - Entering the editor auto-selects the whole text", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, { textEditor: true });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.waitForFirstRender();

  // Select the existing text shape and enter edit mode via Enter
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();

  // Copying while editing exports only the selected text as raw text.
  // Since we just entered the editor, the whole text should be selected.
  await workspace.copy("keyboard");

  // Assert the text was copied correctly
  const copiedText = await page.evaluate(() => navigator.clipboard.readText());
  expect(copiedText).toBe("Lorem ipsum");
});

test.describe("BUG 10934 - Double-clicking a text side handle sets auto-size", () => {
  // Sets up the workspace and loads a text shape whose size is larger than its text
  async function setupFixedSizeText(page) {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    // Enable token inputs so they use the new component with accessible DOM
    await workspace.mockConfigFlags(["enable-feature-token-input"]);
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("text-editor/get-file-fixed-size-text.json");
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    // Select the text and zoom to fit, so it is fully visible in the viewport
    await workspace.clickLeafLayer("Fixed text");
    await page.keyboard.press("Shift+1");
    await workspace.waitForIdle();

    return workspace;
  }

  async function doubleClickSideHandle(workspace, position) {
    const handle = workspace.viewport.getByTestId(
      `resize-side-handler-${position}`,
    );
    await handle.waitFor();
    const box = await handle.boundingBox();
    await workspace.page.mouse.dblclick(
      box.x + box.width / 2,
      box.y + box.height / 2,
    );
  }

  function measureInput(workspace, name) {
    return workspace.rightSidebar
      .getByRole("region", { name: "shape-measures-section" })
      .getByRole("textbox", { name, exact: true });
  }

  test("Double-clicking the right handle switches to auto-width", async ({
    page,
  }) => {
    const workspace = await setupFixedSizeText(page);

    const widthInput = workspace.rightSidebar
      .getByRole("region", { name: "shape-measures-section" })
      .getByRole("textbox", { name: "Width", exact: true });
    const initialWidth = Number(await widthInput.inputValue());

    await doubleClickSideHandle(workspace, "right");

    // Assert auto-width is selected and that the width has shrunk. The resize
    // is debounced, so poll the value (auto-retrying) rather than reading once.
    await expect(
      workspace.rightSidebar.getByRole("button", {
        name: "Auto width",
        pressed: true,
      }),
    ).toBeVisible();
    await expect
      .poll(async () => Number(await widthInput.inputValue()))
      .toBeLessThan(initialWidth);
  });

  test("Double-clicking the bottom handle switches to auto-height", async ({
    page,
  }) => {
    const workspace = await setupFixedSizeText(page);

    const heightInput = workspace.rightSidebar
      .getByRole("region", { name: "shape-measures-section" })
      .getByRole("textbox", { name: "Height", exact: true });
    const initialHeight = Number(await heightInput.inputValue());

    await doubleClickSideHandle(workspace, "bottom");

    // Assert auto-height is selected and that the height has shrunk. The resize
    // is debounced, so poll the value (auto-retrying) rather than reading once.
    await expect(
      workspace.rightSidebar.getByRole("button", {
        name: "Auto height",
        pressed: true,
      }),
    ).toBeVisible();
    await expect
      .poll(async () => Number(await heightInput.inputValue()))
      .toBeLessThan(initialHeight);
  });
});

test.describe("Text Editor context menu", () => {
  // Loads a file that already holds a text shape and enters edit mode, which
  // leaves the whole text selected.
  async function editText(page) {
    const workspace = new WasmWorkspacePage(page, { textEditor: true });
    await workspace.setupEmptyFile();
    await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
    await workspace.goToWorkspace();
    await workspace.waitForFirstRender();

    await workspace.clickLeafLayer("Lorem ipsum");
    await workspace.textEditor.startEditing();

    return workspace;
  }

  // Right-clicks over the first characters of the text. The capture surface
  // starts at the shape's top left corner, so its own box gives us the point.
  async function rightClickOnText(workspace) {
    const box = await workspace.page
      .getByTestId("text-editor-container")
      .boundingBox();
    await workspace.page.mouse.click(box.x + 10, box.y + box.height / 2, {
      button: "right",
    });
  }

  function contextMenu(page) {
    return page.getByTestId("context-menu");
  }

  function menuItem(page, name) {
    return contextMenu(page).getByRole("listitem").filter({ hasText: name });
  }

  function readClipboard(page) {
    return page.evaluate(() => navigator.clipboard.readText());
  }

  test("Right-clicking the text being edited opens the text menu", async ({
    page,
  }) => {
    const workspace = await editText(page);

    await rightClickOnText(workspace);

    await expect(menuItem(page, "Cut")).toBeVisible();
    await expect(menuItem(page, "Copy")).toBeVisible();
    await expect(menuItem(page, "Paste")).toBeVisible();
    await expect(menuItem(page, "Select all")).toBeVisible();

    // The menu acts on the text, so the shape entries are not offered.
    await expect(menuItem(page, "Duplicate")).toHaveCount(0);
  });

  test("Copying from the menu copies the selected text", async ({ page }) => {
    const workspace = await editText(page);

    // Entering the editor selects the whole text, and the right click keeps it.
    await rightClickOnText(workspace);
    await menuItem(page, "Copy").click();

    await expect.poll(() => readClipboard(page)).toBe("Lorem ipsum");
  });

  test("Cutting from the menu removes the selected text", async ({ page }) => {
    const workspace = await editText(page);

    await rightClickOnText(workspace);
    await menuItem(page, "Cut").click();

    await expect.poll(() => readClipboard(page)).toBe("Lorem ipsum");
    await expect(contextMenu(page)).toBeHidden();

    // The text is gone, so what we type now is all the shape has left. Typing
    // also shows the editor kept the focus while the menu was open.
    await page.keyboard.type("ok");
    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("ok");
  });

  test("Pasting from the menu replaces the selection", async ({ page }) => {
    const workspace = await editText(page);

    await page.evaluate(() => navigator.clipboard.writeText("pasted"));

    // The whole text is selected, so the paste replaces it.
    await rightClickOnText(workspace);
    await menuItem(page, "Paste").click();
    await expect(contextMenu(page)).toBeHidden();

    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("pasted");
  });

  test("Selecting all from the menu selects the whole text", async ({
    page,
  }) => {
    const workspace = await editText(page);

    // Collapse the selection the editor starts with.
    await page.keyboard.press("End");

    await rightClickOnText(workspace);
    await menuItem(page, "Select all").click();
    await expect(contextMenu(page)).toBeHidden();

    // Everything is selected again, so typing replaces the whole text.
    await page.keyboard.type("ok");
    await workspace.textEditor.stopEditing();

    await workspace.layers.getByTestId("layer-row").first().click();
    await workspace.waitForSelectedShapeName("ok");
  });

  // Presses a button over the text, moves and releases: the gesture that used to
  // start a drag-selection and destroy whatever was selected.
  async function dragOverText(workspace, button) {
    const { page } = workspace;
    const box = await page.getByTestId("text-editor-container").boundingBox();
    const y = box.y + box.height / 2;

    await page.mouse.move(box.x + 10, y);
    await page.mouse.down({ button });
    // Steps, so the moves are actually delivered to the page.
    await page.mouse.move(box.x + 40, y, { steps: 10 });
    await page.mouse.up({ button });
  }

  // Copies with the keyboard and resolves with what reached the clipboard. The
  // sentinel keeps a value left by an earlier test from passing as a result.
  async function copiedText(page) {
    await page.evaluate(() => navigator.clipboard.writeText("sentinel"));
    await page.keyboard.press("ControlOrMeta+C");
    return readClipboard(page);
  }

  test("The selection survives a right button drag", async ({ page }) => {
    const workspace = await editText(page);

    await dragOverText(workspace, "right");

    // The whole text is still selected, so the copy takes all of it.
    await expect.poll(() => copiedText(page)).toBe("Lorem ipsum");
  });

  test("Moving the caret with a right click drops the pending style", async ({
    page,
  }) => {
    const workspace = await editText(page);
    const fontSize = workspace.textEditor.fontSize;

    // Collapse the caret at the end and stage a font size for whatever is typed
    // next, which is what the sidebar does at a collapsed caret.
    await page.keyboard.press("End");
    const originalSize = await fontSize.inputValue();
    const newSize = String(Number(originalSize) + 20);
    await workspace.textEditor.changeFontSize(newSize);

    // The right click moves the caret, which abandons that staged size. The menu
    // is left open on purpose: Escape would clear the staged size by itself, and
    // the assertion below would then hold whether or not the caret move did.
    await rightClickOnText(workspace);
    await expect(contextMenu(page)).toBeVisible();

    await page.keyboard.type("X");

    // The typed character takes the size of the text around it, not the one
    // staged for the caret position we left behind.
    await page.keyboard.press("Shift+ArrowLeft");
    await expect(fontSize).toHaveValue(originalSize);
  });

  test("Cut and copy are disabled when there is no selection", async ({
    page,
  }) => {
    const workspace = await editText(page);

    // Collapse the selection the editor starts with.
    await page.keyboard.press("End");

    await rightClickOnText(workspace);

    await expect(menuItem(page, "Cut")).toHaveAttribute("disabled");
    await expect(menuItem(page, "Copy")).toHaveAttribute("disabled");
    await expect(menuItem(page, "Paste")).not.toHaveAttribute("disabled");
    await expect(menuItem(page, "Select all")).not.toHaveAttribute("disabled");
  });
});
