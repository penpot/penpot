import { expect, describe, test } from "vitest";
import {
  createEmptyParagraph,
  createParagraph,
} from "../content/dom/Paragraph.js";
import { createTextSpan } from "../content/dom/TextSpan.js";
import { createLineBreak } from "../content/dom/LineBreak.js";
import { TextEditorMock } from "../../test/TextEditorMock.js";
import { SelectionController } from "./SelectionController.js";
import { SelectionDirection } from "./SelectionDirection.js";

/* @vitest-environment jsdom */

/**
 * Utility function to make focus and selections work properly in JSDOM.
 *
 * @param {Selection} selection
 * @param {TextEditor} textEditor
 * @param {Node} focusNode
 * @param {number} [focusOffset=0]
 * @param {Node} [anchorNode=null]
 * @param {number} [anchorOffset=0]
 */
function focus(
  selection,
  textEditor,
  focusNode,
  focusOffset = 0,
  anchorNode = focusNode,
  anchorOffset = focusOffset,
) {
  textEditor.element.focus();
  selection.setBaseAndExtent(anchorNode, anchorOffset, focusNode, focusOffset);
  document.dispatchEvent(new Event("selectionchange"));
}

describe("SelectionController", () => {
  test("`selection` should return the Selection object kept by the SelectionController", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("");
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    expect(selectionController.selection).toBe(selection);
  });

  test("`range` should return the Range object kept by the SelectionController", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    // When the editor hasn't been focused
    // range is null.
    expect(selectionController.range).toBe(null);
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.range).toBeInstanceOf(Range);
  });

  test("`focusAtStart` should return `true` if the offset is 0", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.focusAtStart).toBe(true);
  });

  test("`focusAtEnd` should return `true` if the offset is the length of the `textContent`", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "Hello, World!".length,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.focusAtEnd).toBe(true);
  });

  test("`anchorAtStart` should return `true` if the offset is 0", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.anchorAtStart).toBe(true);
  });

  test("`anchorAtEnd` should return `true` if the offset is the length of the `textContent`", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      "Hello, World!".length,
    );
    expect(selectionController.anchorAtEnd).toBe(true);
  });

  test("`direction` should return the direction of the focus and anchor nodes", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.direction).toBe(SelectionDirection.NONE);
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      5,
      root.firstChild.firstChild.firstChild,
      0,
    );
    expect(selectionController.direction).toBe(SelectionDirection.FORWARD);
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.firstChild.firstChild,
      5,
    );
    expect(selectionController.direction).toBe(SelectionDirection.BACKWARD);
  });

  test("`insertText` should insert some text in a Text node", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("Hello");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "Hello".length,
    );
    selectionController.insertText(", World!");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, World!",
    );
  });

  test("`replaceLineBreak` should replace a <br> with some text", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockEmpty();
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(selection, textEditorMock, root.firstChild.firstChild.firstChild);
    selectionController.replaceLineBreak("Hello, World!");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, World!",
    );
  });

  test("`insertPaste` should insert a paragraph from a pasted fragment (at start)", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText(", World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(selection, textEditorMock, root.firstChild.firstChild.firstChild, 0);
    const paragraph = createParagraph([createTextSpan(new Text("Hello"))]);
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello",
    );
    expect(
      textEditorMock.root.lastChild.firstChild.firstChild.nodeValue,
    ).toBe(", World!");
  });

  test("`insertPaste` should insert a paragraph from a pasted fragment (at middle)", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Lorem dolor");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(selection, textEditorMock, root.firstChild.firstChild.firstChild, "Lorem ".length);
    const paragraph = createParagraph([createTextSpan(new Text("ipsum "))]);
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Lorem ipsum dolor");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Lorem ",
    );
    expect(textEditorMock.root.children.item(1).firstChild.firstChild.nodeValue).toBe(
      "ipsum ",
    );
    expect(textEditorMock.root.lastChild.firstChild.firstChild.nodeValue).toBe(
      "dolor",
    );
  });

  test("`insertPaste` should insert a paragraph from a pasted fragment (at end)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("Hello");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "Hello".length,
    );
    const paragraph = createParagraph([createTextSpan(new Text(", World!"))]);
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello",
    );
    expect(
      textEditorMock.root.lastChild.firstChild.firstChild.nodeValue,
    ).toBe(", World!");
  });

  test("`insertPaste` should insert a text span from a pasted fragment (at start)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText(", World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
    );
    const paragraph = createParagraph([createTextSpan(new Text("Hello"))]);
    paragraph.dataset.textSpan = "force";
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello",
    );
    expect(
      textEditorMock.root.firstChild.children.item(1).firstChild.nodeValue,
    ).toBe(", World!");
  });

  test("`insertPaste` should insert an text span from a pasted fragment (at middle)", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Lorem dolor");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(selection, textEditorMock, root.firstChild.firstChild.firstChild, "Lorem ".length);
    const paragraph = createParagraph([createTextSpan(new Text("ipsum "))]);
    paragraph.dataset.textSpan = "force";
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Lorem ipsum dolor");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Lorem ",
    );
    expect(textEditorMock.root.firstChild.children.item(1).firstChild.nodeValue).toBe(
      "ipsum ",
    );
    expect(
      textEditorMock.root.firstChild.children.item(2).firstChild.nodeValue,
    ).toBe("dolor");
  });

  test("`insertPaste` should insert an text span from a pasted fragment (at end)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("Hello");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "Hello".length,
    );
    const paragraph = createParagraph([
      createTextSpan(new Text(", World!"))
    ]);
    paragraph.dataset.textSpan = "force";
    const fragment = document.createDocumentFragment();
    fragment.append(paragraph);

    selectionController.insertPaste(fragment);
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello",
    );
    expect(textEditorMock.root.firstChild.children.item(1).firstChild.nodeValue).toBe(
      ", World!",
    );
  });

  test("`removeBackwardText` should remove text in backward direction (backspace)", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "Hello, World!".length,
    );
    selectionController.removeBackwardText();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, World",
    );
  });

  test("`removeBackwardText` should remove text in backward direction (backspace) and create a new empty paragraph when there's nothing left", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithText("H");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      "H".length,
    );
    selectionController.removeBackwardText();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("");
  });

  test("`mergeBackwardParagraph` should merge two paragraphs in backward direction (backspace)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.childNodes.item(1).firstChild.firstChild,
      0,
    );
    selectionController.mergeBackwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.children.length).toBe(1);
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
  });

  test("`mergeBackwardParagraph` should merge two paragraphs in backward direction (backspace)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createEmptyParagraph(),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.childNodes.item(2).firstChild.firstChild,
      0,
    );
    selectionController.mergeBackwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.textContent).toBe("Hello, ");
    expect(textEditorMock.root.lastChild.textContent).toBe("World!");
  });

  test("`mergeForwardParagraph` should merge two paragraphs in forward direction (backspace)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      root.firstChild.firstChild.firstChild.nodeValue.length,
    );
    selectionController.mergeForwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.children.length).toBe(1);
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
  });

  test("`mergeForwardParagraph` should merge two paragraphs in forward direction (backspace)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createEmptyParagraph(),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.childNodes.item(2).firstChild.firstChild,
      0,
    );
    selectionController.mergeBackwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.textContent).toBe("Hello, ");
    expect(textEditorMock.root.lastChild.textContent).toBe("World!");
  });

  test("`removeForwardText` should remove text in forward direction (delete)", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(selection, textEditorMock, root.firstChild.firstChild.firstChild);
    selectionController.removeForwardText();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("ello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "ello, World!",
    );
  });

  test("`replaceText` should replace the selected text", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      7,
      root.firstChild.firstChild.firstChild,
      12,
    );
    selectionController.replaceText("Mundo");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, Mundo!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, Mundo!",
    );
  });

  test("`replaceTextSpans` should replace the selected text in multiple text spans (2 completelly selected)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, ")),
      createTextSpan(new Text("World!")),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.lastChild.firstChild,
      "World!".length,
    );
    selectionController.replaceTextSpans("Mundo");

    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Mundo");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Mundo",
    );
  });

  test("`replaceTextSpans` should replace the selected text in multiple text spans (2 partially selected)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, ")),
      createTextSpan(new Text("World!")),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      2,
      root.firstChild.lastChild.firstChild,
      "World!".length - 3,
    );
    selectionController.replaceTextSpans("Mundo");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(2);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("HeMundold!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "HeMundo",
    );
    expect(textEditorMock.root.firstChild.lastChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.lastChild.firstChild.nodeValue).toBe(
      "ld!",
    );
  });

  test("`replaceTextSpans` should replace the selected text in multiple text spans (1 partially selected, 1 completelly selected)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, ")),
      createTextSpan(new Text("World!")),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      2,
      root.firstChild.lastChild.firstChild,
      "World!".length,
    );
    selectionController.replaceTextSpans("Mundo");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("HeMundo");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "HeMundo",
    );
  });

  test("`replaceTextSpans` should replace the selected text in multiple text spans (1 completelly selected, 1 partially selected)", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, ")),
      createTextSpan(new Text("World!")),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.lastChild.firstChild,
      "World!".length - 3,
    );
    selectionController.replaceTextSpans("Mundo");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Mundold!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Mundold!",
    );
  });

  test("`removeSelected` removes a word", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      7,
      root.firstChild.lastChild.firstChild,
      "Hello, World!".length - 1,
    );
    selectionController.removeSelected();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, !");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, !",
    );
  });

  test("`removeSelected` multiple text spans", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, ")),
      createTextSpan(new Text("World!")),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      0,
      root.firstChild.lastChild.firstChild,
      "World!".length,
    );
    selectionController.removeSelected();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      HTMLBRElement,
    );
  });

  test("`removeSelected` multiple paragraphs", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, "))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("World!"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.lastElementChild,
      0,
      root.children.item(1).firstChild,
      0,
    );
    selectionController.removeSelected();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, ",
    );
    expect(textEditorMock.root.lastChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.lastChild.firstChild.firstChild.nodeValue).toBe(
      "World!",
    );
  });

  test("`removeSelected` and `removeBackwardParagraph`", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, World!"))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("This is a test"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.lastElementChild.firstElementChild.firstChild, // This is a test text
      0,
      root.lastElementChild.firstElementChild.firstChild,
      "This is a test".length,
    );
    selectionController.removeSelected();
    selectionController.removeBackwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, World!",
    );
  });

  test("`removeSelected` and `removeForwardParagraph`", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, World!"))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("This is a test"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstElementChild.firstElementChild.firstChild, // This is a test text
      0,
      root.firstElementChild.firstElementChild.firstChild,
      "Hello, World!".length,
    );
    selectionController.removeSelected();
    selectionController.removeForwardParagraph();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("This is a test");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      HTMLBRElement,
    );
    expect(textEditorMock.root.lastChild.firstChild.firstChild.nodeValue).toBe(
      "This is a test",
    );
  });

  test("performing a `removeSelected` after a `removeSelected` should do nothing", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, World!"))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("This is a test"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstElementChild.firstElementChild.firstChild, // This is a test text
      0,
      root.firstElementChild.firstElementChild.firstChild,
      "Hello, World!".length,
    );
    selectionController.removeSelected();

    // This should do nothing.
    selectionController.removeSelected();

    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(3);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("This is a test");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      HTMLBRElement,
    );
    expect(textEditorMock.root.lastChild.firstChild.firstChild.nodeValue).toBe(
      "This is a test",
    );
  });

  test("`removeSelected` removes everything", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, World!"))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("This is a test"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstElementChild.firstElementChild.firstChild, // This is a test text
      0,
      root.lastElementChild.firstElementChild.firstChild,
      "This is a test".length,
    );
    selectionController.removeSelected();
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(1);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.textContent).toBe("");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      HTMLBRElement,
    );
  });

  test("`removeSelected` removes everything and insert text", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([createTextSpan(new Text("Hello, World!"))]),
      createParagraph([createTextSpan(createLineBreak())]),
      createParagraph([createTextSpan(new Text("This is a test"))]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstElementChild.firstElementChild.firstChild, // This is a test text
      0,
      root.lastElementChild.firstElementChild.firstChild,
      "This is a test".length,
    );
    selectionController.removeSelected();
    selectionController.replaceLineBreak("Hello, World!");
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children).toHaveLength(1);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.children).toHaveLength(1);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.firstChild.firstChild).toBeInstanceOf(
      Text,
    );
    expect(textEditorMock.root.firstChild.firstChild.firstChild.nodeValue).toBe(
      "Hello, World!",
    );
  });

  test("`applyStyles` to text", () => {
    const textEditorMock =
      TextEditorMock.createTextEditorMockWithText("Hello, World!");
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      root.firstChild.firstChild.firstChild.nodeValue.length - 1,
      root.firstChild.firstChild.firstChild,
      root.firstChild.firstChild.firstChild.nodeValue.length - 6,
    );
    selectionController.applyStyles({
      "font-weight": "bold",
    });
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(1);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.children.length).toBe(3);
    expect(textEditorMock.root.firstChild.firstChild.dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild.children.item(0).textContent).toBe(
      "Hello, ",
    );
    expect(textEditorMock.root.firstChild.children.item(1).textContent).toBe(
      "World",
    );
    expect(textEditorMock.root.firstChild.children.item(2).textContent).toBe(
      "!",
    );
  });

  test("`applyStyles` to text spans", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraph([
      createTextSpan(new Text("Hello, "), {
        "font-style": "italic",
      }),
      createTextSpan(new Text("World!"), {
        "font-style": "oblique",
      }),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      2,
      root.firstChild.lastChild.firstChild,
      root.firstChild.lastChild.firstChild.nodeValue.length - 3,
    );
    selectionController.applyStyles({
      "font-weight": "bold",
    });
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(1);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.children.length).toBe(4);
    expect(textEditorMock.root.firstChild.children.item(0).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(0).textContent).toBe(
      "He",
    );
    expect(textEditorMock.root.firstChild.children.item(1).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(1).textContent).toBe(
      "llo, ",
    );
    expect(textEditorMock.root.firstChild.children.item(2).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(2).textContent).toBe(
      "Wor",
    );
    expect(textEditorMock.root.firstChild.children.item(3).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(3).textContent).toBe(
      "ld!",
    );
  });

  test("`applyStyles` to paragraphs", () => {
    const textEditorMock = TextEditorMock.createTextEditorMockWithParagraphs([
      createParagraph([
        createTextSpan(new Text("Hello, "), {
          "font-style": "italic",
        }),
      ]),
      createParagraph([
        createTextSpan(new Text("World!"), {
          "font-style": "oblique",
        }),
      ]),
    ]);
    const root = textEditorMock.root;
    const selection = document.getSelection();
    const selectionController = new SelectionController(
      textEditorMock,
      selection,
    );
    focus(
      selection,
      textEditorMock,
      root.firstChild.firstChild.firstChild,
      2,
      root.lastChild.firstChild.firstChild,
      root.lastChild.firstChild.firstChild.nodeValue.length - 3,
    );
    selectionController.applyStyles({
      "font-weight": "bold",
    });
    expect(textEditorMock.root).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.children.length).toBe(2);
    expect(textEditorMock.root.dataset.itype).toBe("root");
    expect(textEditorMock.root.textContent).toBe("Hello, World!");
    expect(textEditorMock.root.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(textEditorMock.root.firstChild.dataset.itype).toBe("paragraph");
    expect(textEditorMock.root.firstChild.firstChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(textEditorMock.root.firstChild.children.length).toBe(2);
    expect(textEditorMock.root.firstChild.children.item(0).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(0).textContent).toBe(
      "He",
    );
    expect(textEditorMock.root.firstChild.children.item(1).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.firstChild.children.item(1).textContent).toBe(
      "llo, ",
    );
    expect(textEditorMock.root.lastChild.children.length).toBe(2);
    expect(textEditorMock.root.lastChild.children.item(0).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.lastChild.children.item(0).textContent).toBe(
      "Wor",
    );
    expect(textEditorMock.root.lastChild.children.item(1).dataset.itype).toBe(
      "span",
    );
    expect(textEditorMock.root.lastChild.children.item(1).textContent).toBe(
      "ld!",
    );
  });
});
