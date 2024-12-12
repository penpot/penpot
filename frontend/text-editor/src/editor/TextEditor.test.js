import { describe, test, expect } from "vitest";
import { TextEditor } from "./TextEditor.js";

/* @vitest-environment jsdom */
describe("TextEditor", () => {
  test("Creating TextEditor without element should throw", () => {
    expect(() => new TextEditor()).toThrowError("Invalid text editor element");
  });

  test("Creating TextEditor with element should success", () => {
    expect(new TextEditor(document.createElement("div"))).toBeInstanceOf(
      TextEditor,
    );
  });

  test("isEmpty should return true when editor is empty", () => {
    const textEditor = new TextEditor(document.createElement("div"));
    expect(textEditor).toBeInstanceOf(TextEditor);
    expect(textEditor.isEmpty).toBe(true);
  });

  test("Num paragraphs should return 1 when empty", () => {
    const textEditor = new TextEditor(document.createElement("div"));
    expect(textEditor).toBeInstanceOf(TextEditor);
    expect(textEditor.numParagraphs).toBe(1);
  });

  test("Num paragraphs should return the number of paragraphs", () => {
    const textEditor = new TextEditor(document.createElement("div"));
    textEditor.root = textEditor.createRoot([
      textEditor.createParagraph([
        textEditor.createInlineFromString("Hello, World!"),
      ]),
      textEditor.createParagraph([textEditor.createInlineFromString("")]),
      textEditor.createParagraph([
        textEditor.createInlineFromString("¡Hola, Mundo!"),
      ]),
      textEditor.createParagraph([
        textEditor.createInlineFromString("Hallo, Welt!"),
      ]),
    ]);
    expect(textEditor).toBeInstanceOf(TextEditor);
    expect(textEditor.numParagraphs).toBe(4);
  });

  test("Disposing a TextEditor nullifies everything", () => {
    const textEditor = new TextEditor(document.createElement("div"));
    expect(textEditor).toBeInstanceOf(TextEditor);
    textEditor.dispose();
    expect(textEditor.root).toBe(null);
    expect(textEditor.element).toBe(null);
  });

  test("TextEditor focus should focus the contenteditable element", () => {
    const textEditorElement = document.createElement("div");
    document.body.appendChild(textEditorElement);
    const textEditor = new TextEditor(textEditorElement);
    expect(textEditor).toBeInstanceOf(TextEditor);
    textEditor.focus();
    expect(document.activeElement).toBe(textEditor.element);
  });

  test("TextEditor blur should blur the contenteditable element", () => {
    const textEditorElement = document.createElement("div");
    document.body.appendChild(textEditorElement);
    const textEditor = new TextEditor(textEditorElement);
    expect(textEditor).toBeInstanceOf(TextEditor);
    textEditor.focus();
    textEditor.blur();
    expect(document.activeElement).not.toBe(textEditor.element);
  });

  test("TextEditor focus -> blur -> focus should restore old selection", () => {
    const textEditorElement = document.createElement("div");
    document.body.appendChild(textEditorElement);
    const textEditor = new TextEditor(textEditorElement);
    textEditor.root = textEditor.createRoot([
      textEditor.createParagraph([
        textEditor.createInlineFromString("Hello, World!"),
      ]),
    ]);
    expect(textEditor).toBeInstanceOf(TextEditor);
    textEditor.focus();
    textEditor.blur();
    textEditor.focus();
    expect(document.activeElement).toBe(textEditor.element);
  });

  test("TextEditor selectAll should select all the contenteditable", () => {
    const selection = document.getSelection();
    const textEditorElement = document.createElement("div");
    document.body.appendChild(textEditorElement);
    const textEditor = new TextEditor(textEditorElement);
    expect(textEditor).toBeInstanceOf(TextEditor);
    textEditor.focus();
    textEditor.selectAll();
    expect(document.activeElement).toBe(textEditor.element);
    expect(selection.containsNode(textEditor.root));
  });
});
