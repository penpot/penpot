import { describe, test, expect } from "vitest";
import {
  isTextNode,
  getTextNodeLength,
  resolveTextNodePosition,
} from "./TextNode.js";
import { createLineBreak } from "./LineBreak.js";
import { createTextSpan, createEmptyTextSpan } from "./TextSpan.js";
import { createParagraph } from "./Paragraph.js";
import { createRoot } from "./Root.js";

/* @vitest-environment jsdom */
describe("TextNode", () => {
  test("isTextNode should return true when the passed node is a Text", () => {
    expect(isTextNode(new Text("Hello, World!"))).toBe(true);
    expect(isTextNode(Infinity)).toBe(false);
    expect(isTextNode(true)).toBe(false);
    expect(isTextNode("hola")).toBe(false);
    expect(isTextNode({})).toBe(false);
    expect(isTextNode([])).toBe(false);
    expect(() => isTextNode(undefined)).toThrowError("Invalid text node");
    expect(() => isTextNode(null)).toThrowError("Invalid text node");
    expect(() => isTextNode(0)).toThrowError("Invalid text node");
  });

  test("getTextNodeLength should return the length of the text node or 0 if it is a <br>", () => {
    expect(getTextNodeLength(new Text("Hello, World!"))).toBe(13);
    expect(getTextNodeLength(createLineBreak())).toBe(0);
    expect(() => getTextNodeLength(undefined)).toThrowError(
      "Invalid text node",
    );
    expect(() => getTextNodeLength(null)).toThrowError("Invalid text node");
    expect(() => getTextNodeLength(0)).toThrowError("Invalid text node");
  });

  describe("resolveTextNodePosition", () => {
    test("should return the same position when the node is already a text node", () => {
      const textNode = new Text("Hello, World!");
      expect(resolveTextNodePosition(textNode, 5)).toStrictEqual({
        node: textNode,
        offset: 5,
      });
    });

    test("should return the same position when the node is a line break", () => {
      const lineBreak = createLineBreak();
      expect(resolveTextNodePosition(lineBreak, 0)).toStrictEqual({
        node: lineBreak,
        offset: 0,
      });
    });

    test("should resolve a text span to its child at the given index", () => {
      const textNode = new Text("Hello");
      const textSpan = createTextSpan(textNode);
      expect(resolveTextNodePosition(textSpan, 0)).toStrictEqual({
        node: textNode,
        offset: 0,
      });
    });

    test("should resolve a text span index past the last child to the end of its text", () => {
      const textNode = new Text("Hello");
      const textSpan = createTextSpan(textNode);
      expect(resolveTextNodePosition(textSpan, 1)).toStrictEqual({
        node: textNode,
        offset: 5,
      });
    });

    test("should resolve a paragraph to the text node of the indexed text span", () => {
      const first = new Text("Hello, ");
      const second = new Text("World!");
      const paragraph = createParagraph([
        createTextSpan(first),
        createTextSpan(second),
      ]);
      expect(resolveTextNodePosition(paragraph, 0)).toStrictEqual({
        node: first,
        offset: 0,
      });
      expect(resolveTextNodePosition(paragraph, 1)).toStrictEqual({
        node: second,
        offset: 0,
      });
      expect(resolveTextNodePosition(paragraph, 2)).toStrictEqual({
        node: second,
        offset: 6,
      });
    });

    test("should resolve an empty paragraph to its line break", () => {
      const textSpan = createEmptyTextSpan();
      const paragraph = createParagraph([textSpan]);
      expect(resolveTextNodePosition(paragraph, 0)).toStrictEqual({
        node: textSpan.firstChild,
        offset: 0,
      });
    });

    test("should resolve a root to the text node of the indexed paragraph", () => {
      const first = new Text("Hello, ");
      const second = new Text("World!");
      const root = createRoot([
        createParagraph([createTextSpan(first)]),
        createParagraph([createTextSpan(second)]),
      ]);
      expect(resolveTextNodePosition(root, 1)).toStrictEqual({
        node: second,
        offset: 0,
      });
    });

    test("should resolve an editor element to the first text node of its root", () => {
      const textNode = new Text("Hello");
      const root = createRoot([createParagraph([createTextSpan(textNode)])]);
      const editor = document.createElement("div");
      editor.dataset.itype = "editor";
      editor.appendChild(root);
      expect(resolveTextNodePosition(editor, 0)).toStrictEqual({
        node: textNode,
        offset: 0,
      });
    });

    test("should return null instead of throwing when the position cannot be resolved", () => {
      expect(resolveTextNodePosition(null, 0)).toBe(null);
      expect(resolveTextNodePosition(undefined, 0)).toBe(null);
      expect(resolveTextNodePosition(document.createElement("div"), 0)).toBe(
        null,
      );
      expect(resolveTextNodePosition(createParagraph([]), 0)).toBe(null);
    });
  });
});
