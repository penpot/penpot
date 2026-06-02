import { describe, test, expect } from "vitest";
import { isTextNode, getTextNodeLength } from "./TextNode.js";
import { createLineBreak } from "./LineBreak.js";

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
});
