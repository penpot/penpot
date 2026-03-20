import { test, expect } from "@playwright/test";
import { Clipboard } from "../../helpers/Clipboard";
import { WasmWorkspacePage } from "../pages/WasmWorkspacePage";

test.beforeEach(async ({ page, context }) => {
  await Clipboard.enable(context, Clipboard.Permission.ALL);

  await WasmWorkspacePage.init(page);
  await WasmWorkspacePage.mockConfigFlags(page, ["enable-feature-text-editor-v2"]);
});

test.afterEach(async ({ context }) => {
  await context.clearPermissions();
});

test("Create a new text shape", async ({ page }) => {
  const initialText = "Lorem ipsum";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();
  await workspace.createTextShape(190, 150, 300, 200, initialText);

  await workspace.textEditor.stopEditing();

  await workspace.waitForSelectedShapeName(initialText);
});

test("Create a new text shape from pasting text", async ({ page }) => {
  const textToPaste = "Lorem ipsum";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();
  await workspace.moveButton.click();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickAt(190, 150);
  await workspace.paste("keyboard");

  await workspace.textEditor.stopEditing();

  await expect(workspace.layers.getByText(textToPaste)).toBeVisible();
});

test("Create a new text shape from pasting text using context menu", async ({
  page,
}) => {
  const textToPaste = "Lorem ipsum";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();
  await workspace.moveButton.click();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickAt(190, 150);
  await workspace.paste("context-menu");
  await workspace.textEditor.stopEditing();

  await expect(workspace.layers.getByText(textToPaste)).toBeVisible();
});

test("Update an already created text shape by appending text", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromEnd(0);
  await page.keyboard.type(" dolor sit amet");
  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Lorem ipsum dolor sit amet");
});

test("Update an already created text shape by prepending text", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromStart(0);
  await page.evaluate(() => new Promise((resolve) => globalThis.requestIdleCallback(resolve)));
  await page.keyboard.type("Dolor sit amet ");
  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Dolor sit amet Lorem ipsum");
});

test.skip("Update an already created text shape by inserting text in between", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromStart(5);
  await page.keyboard.type(" dolor sit amet");
  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Lorem dolor sit amet ipsum");
});

test("Update a new text shape appending text by pasting text", async ({
  page,
}) => {
  const textToPaste = " dolor sit amet";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromEnd();
  await workspace.paste("keyboard");
  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Lorem ipsum dolor sit amet");
});

test.skip("Update a new text shape prepending text by pasting text", async ({
  page,
}) => {
  const textToPaste = "Dolor sit amet ";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromStart();
  await workspace.paste("keyboard");
  await workspace.textEditor.stopEditing();

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Update a new text shape replacing (starting) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "Dolor sit amet";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5);

  await Clipboard.writeText(page, textToPaste);

  await workspace.paste("keyboard");

  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Dolor sit amet ipsum");
});

test("Update a new text shape replacing (ending) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "dolor sit amet";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromEnd(5);

  await Clipboard.writeText(page, textToPaste);

  await workspace.paste("keyboard");

  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Lorem dolor sit amet");
});

test("Update a new text shape replacing (in between) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "dolor sit amet";
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5, 3);

  await Clipboard.writeText(page, textToPaste);

  await workspace.paste("keyboard");

  await workspace.textEditor.stopEditing();
  await workspace.waitForSelectedShapeName("Lordolor sit ametsum");
});

test("Update text font size selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5);
  await workspace.textEditor.changeFontSize(36);
  await workspace.textEditor.stopEditing();

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("Update text line height selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5);
  await workspace.textEditor.changeLineHeight(4.4);
  await workspace.textEditor.stopEditing();

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test.skip("Update text letter spacing selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WasmWorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5);
  await workspace.textEditor.changeLetterSpacing(10);
  await workspace.textEditor.stopEditing();

  await workspace.hideUI();
  await expect(workspace.canvas).toHaveScreenshot();
});

test("BUG 11552 - Apply styles to the current caret", async ({ page }) => {
  const workspace = new WasmWorkspacePage(page);
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-11552.json");
  await workspace.mockRPC(
    "update-file?id=*",
    "text-editor/update-file-11552.json",
  );
  await workspace.goToWorkspace();
  await workspace.doubleClickLeafLayer("Lorem ipsum");

  const fontSizeInput = workspace.rightSidebar.getByRole("textbox", {
    name: "Font Size",
  });
  await expect(fontSizeInput).toBeVisible();

  await page.keyboard.press("Enter");
  await page.keyboard.press("ArrowRight");

  await fontSizeInput.fill("36");

  await workspace.clickLeafLayer("Lorem ipsum");

  // display Mixed placeholder
  await expect(fontSizeInput).toHaveValue("");
  await expect(fontSizeInput).toHaveAttribute("placeholder", "Mixed");
});
