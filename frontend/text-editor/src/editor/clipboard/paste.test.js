import { describe, test, expect } from "vitest";
import { TextEditorMock } from "../../test/TextEditorMock.js";
import { SelectionController } from "../controllers/SelectionController.js";
import { paste } from "./paste.js";

/* @vitest-environment jsdom */

/**
 * Creates a minimal `ClipboardEvent`-like object carrying plain text.
 *
 * @param {string} text
 * @returns {object}
 */
function createPlainTextClipboardEvent(text) {
  return {
    preventDefault() {},
    clipboardData: {
      types: ["text/plain"],
      getData(type) {
        return type === "text/plain" ? text : "";
      },
    },
  };
}

describe("paste", () => {
  test("should insert plain text into an empty editor that was just focused", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("");
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    textEditorMock.element.focus();

    paste(
      createPlainTextClipboardEvent("Hello, World!"),
      textEditorMock,
      selectionController,
    );

    expect(textEditorMock.root.textContent).toBe("Hello, World!");
  });

  test("should insert plain text when the caret is on a paragraph element", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, ");
    const root = textEditorMock.root;
    const paragraph = root.firstChild;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    textEditorMock.element.focus();
    selection.setBaseAndExtent(paragraph, 1, paragraph, 1);
    document.dispatchEvent(new Event("selectionchange"));

    paste(
      createPlainTextClipboardEvent("World!"),
      textEditorMock,
      selectionController,
    );

    expect(root.textContent).toBe("Hello, World!");
  });
});
