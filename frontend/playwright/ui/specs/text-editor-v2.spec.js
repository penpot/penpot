import { test, expect } from "@playwright/test";
import { Clipboard } from "../../helpers/Clipboard";
import { WorkspacePage } from "../pages/WorkspacePage";

const timeToWait = 100;

test.beforeEach(async ({ page, context }) => {
  await Clipboard.enable(context, Clipboard.Permission.ONLY_WRITE);

  await WorkspacePage.init(page);
  await WorkspacePage.mockConfigFlags(page, ["enable-feature-text-editor-v2"]);
});

test.afterEach(async ({ context }) => {
  context.clearPermissions();
});

test("Create a new text shape", async ({ page }) => {
  const initialText = "Lorem ipsum";
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();
  await workspace.createTextShape(190, 150, 300, 200, initialText);

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe(initialText);

  await workspace.textEditor.stopEditing();
});

test("Create a new text shape from pasting text", async ({ page, context }) => {
  const textToPaste = "Lorem ipsum";
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickAt(190, 150);
  await workspace.paste("keyboard");

  await page.waitForTimeout(timeToWait);

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe(textToPaste);

  await workspace.textEditor.stopEditing();
});

test("Create a new text shape from pasting text using context menu", async ({
  page,
  context,
}) => {
  const textToPaste = "Lorem ipsum";
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.goToWorkspace();

  await Clipboard.writeText(page, textToPaste);

  await workspace.clickAt(190, 150);
  await workspace.paste("context-menu");

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe(textToPaste);

  await workspace.textEditor.stopEditing();
});

test("Update an already created text shape by appending text", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromEnd(0);
  await page.keyboard.type(" dolor sit amet");
  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lorem ipsum dolor sit amet");
  await workspace.textEditor.stopEditing();
});

test("Update an already created text shape by prepending text", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromStart(0);
  await page.keyboard.type("Dolor sit amet ");
  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Dolor sit amet Lorem ipsum");
  await workspace.textEditor.stopEditing();
});

test("Update an already created text shape by inserting text in between", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.moveFromStart(5);
  await page.keyboard.type(" dolor sit amet");
  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lorem dolor sit amet ipsum");
  await workspace.textEditor.stopEditing();
});

test("Update a new text shape appending text by pasting text", async ({
  page,
  context,
}) => {
  const textToPaste = " dolor sit amet";
  const workspace = new WorkspacePage(page, {
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
  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lorem ipsum dolor sit amet");
  await workspace.textEditor.stopEditing();
});

test("Update a new text shape prepending text by pasting text", async ({
  page,
  context,
}) => {
  const textToPaste = "Dolor sit amet ";
  const workspace = new WorkspacePage(page, {
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
  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Dolor sit amet Lorem ipsum");
  await workspace.textEditor.stopEditing();
});

test("Update a new text shape replacing (starting) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "Dolor sit amet";
  const workspace = new WorkspacePage(page, {
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

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Dolor sit amet ipsum");

  await workspace.textEditor.stopEditing();
});

test("Update a new text shape replacing (ending) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "dolor sit amet";
  const workspace = new WorkspacePage(page, {
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

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lorem dolor sit amet");

  await workspace.textEditor.stopEditing();
});

test("Update a new text shape replacing (in between) text with pasted text", async ({
  page,
}) => {
  const textToPaste = "dolor sit amet";
  const workspace = new WorkspacePage(page, {
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

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lordolor sit ametsum");

  await workspace.textEditor.stopEditing();
});

test("Update text font size selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
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

  const textContent1 = await workspace.textEditor.waitForTextSpanContent(1);
  expect(textContent1).toBe("Lorem");
  const textContent2 = await workspace.textEditor.waitForTextSpanContent(2);
  expect(textContent2).toBe(" ipsum");
  await workspace.textEditor.stopEditing();
});

test.skip("Update text line height selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
    textEditor: true,
  });
  await workspace.setupEmptyFile();
  await workspace.mockGetFile("text-editor/get-file-lorem-ipsum.json");
  await workspace.mockRPC("update-file?id=*", "text-editor/update-file.json");
  await workspace.goToWorkspace();
  await workspace.clickLeafLayer("Lorem ipsum");
  await workspace.textEditor.startEditing();
  await workspace.textEditor.selectFromStart(5);
  await workspace.textEditor.changeLineHeight(1.4);

  const lineHeight = await workspace.textEditor.waitForParagraphStyle(
    1,
    "line-height",
  );
  expect(lineHeight).toBe("1.4");

  const textContent = await workspace.textEditor.waitForTextSpanContent();
  expect(textContent).toBe("Lorem ipsum");

  await workspace.textEditor.stopEditing();
});

test.skip("Update text letter spacing selecting a part of it (starting)", async ({
  page,
}) => {
  const workspace = new WorkspacePage(page, {
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

  const textContent1 = await workspace.textEditor.waitForTextSpanContent(1);
  expect(textContent1).toBe("Lorem");
  const textContent2 = await workspace.textEditor.waitForTextSpanContent(2);
  expect(textContent2).toBe(" ipsum");
  await workspace.textEditor.stopEditing();
});

test("BUG 11552 - Apply styles to the current caret", async ({ page }) => {
  const workspace = new WorkspacePage(page);
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
