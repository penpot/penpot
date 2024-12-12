import { describe, test, expect } from "vitest";
import {
  createEmptyInline,
  createInline,
  getInline,
  getInlineLength,
  isInline,
  isInlineEnd,
  isInlineStart,
  isLikeInline,
  splitInline,
  TAG,
  TYPE,
} from "./Inline.js";
import { createLineBreak } from "./LineBreak.js";

/* @vitest-environment jsdom */
describe("Inline", () => {
  test("createInline should throw when passed an invalid child", () => {
    expect(() => createInline("Hello, World!")).toThrowError(
      "Invalid inline child",
    );
  });

  test("createInline creates a new inline element with a <br> inside", () => {
    const inline = createInline(createLineBreak());
    expect(inline).toBeInstanceOf(HTMLSpanElement);
    expect(inline.dataset.itype).toBe(TYPE);
    expect(inline.nodeName).toBe(TAG);
    expect(inline.textContent).toBe("");
    expect(inline.firstChild).toBeInstanceOf(HTMLBRElement);
  });

  test("createInline creates a new inline element with a text inside", () => {
    const inline = createInline(new Text("Hello, World!"));
    expect(inline).toBeInstanceOf(HTMLSpanElement);
    expect(inline.dataset.itype).toBe(TYPE);
    expect(inline.nodeName).toBe(TAG);
    expect(inline.textContent).toBe("Hello, World!");
    expect(inline.firstChild).toBeInstanceOf(Text);
  });

  test("createEmptyInline creates a new empty inline element with a <br> inside", () => {
    const emptyInline = createEmptyInline();
    expect(emptyInline).toBeInstanceOf(HTMLSpanElement);
    expect(emptyInline.dataset.itype).toBe(TYPE);
    expect(emptyInline.nodeName).toBe(TAG);
    expect(emptyInline.textContent).toBe("");
    expect(emptyInline.firstChild).toBeInstanceOf(HTMLBRElement);
  });

  test("isInline should return true on elements that are inlines", () => {
    const inline = createInline(new Text("Hello, World!"));
    expect(isInline(inline)).toBe(true);
    const a = document.createElement("a");
    expect(isInline(a)).toBe(false);
    const b = null;
    expect(isInline(b)).toBe(false);
    const c = document.createElement("span");
    expect(isInline(c)).toBe(false);
  });

  test("isLikeInline should return true on elements that have inline behavior by default", () => {
    expect(isLikeInline(Infinity)).toBe(false);
    expect(isLikeInline(null)).toBe(false);
    expect(isLikeInline(document.createElement("A"))).toBe(true);
  });

  // FIXME: Should throw?
  test("isInlineStart returns false when passed node is not an inline", () => {
    const inline = document.createElement("div");
    expect(isInlineStart(inline, 0)).toBe(false);
    expect(isInlineStart(inline, "Hello, World!".length)).toBe(false);
  });

  test("isInlineStart returns if we're at the start of an inline", () => {
    const inline = createInline(new Text("Hello, World!"));
    expect(isInlineStart(inline, 0)).toBe(true);
    expect(isInlineStart(inline, "Hello, World!".length)).toBe(false);
  });

  // FIXME: Should throw?
  test("isInlineEnd returns false when passed node is not an inline", () => {
    const inline = document.createElement("div");
    expect(isInlineEnd(inline, 0)).toBe(false);
    expect(isInlineEnd(inline, "Hello, World!".length)).toBe(false);
  });

  test("isInlineEnd returns if we're in the end of an inline", () => {
    const inline = createInline(new Text("Hello, World!"));
    expect(isInlineEnd(inline, 0)).toBe(false);
    expect(isInlineEnd(inline, "Hello, World!".length)).toBe(true);
  });

  test("getInline ", () => {
    expect(getInline(null)).toBe(null);
  });

  test("getInlineLength throws when the passed node is not an inline", () => {
    const inline = document.createElement("div");
    expect(() => getInlineLength(inline)).toThrowError("Invalid inline");
  });

  test("getInlineLength returns the length of the inline content", () => {
    const inline = createInline(new Text("Hello, World!"));
    expect(getInlineLength(inline)).toBe(13);
  });

  test("getInlineLength should return 0 when the inline content is a <br>", () => {
    const emptyInline = createEmptyInline();
    expect(getInlineLength(emptyInline)).toBe(0);
  });

  test("splitInline returns a new inline from the splitted inline", () => {
    const inline = createInline(new Text("Hello, World!"));
    const newInline = splitInline(inline, 5);
    expect(newInline).toBeInstanceOf(HTMLSpanElement);
    expect(newInline.firstChild).toBeInstanceOf(Text);
    expect(newInline.textContent).toBe(", World!");
    expect(newInline.dataset.itype).toBe(TYPE);
    expect(newInline.nodeName).toBe(TAG);
  });
});
