import { describe, test, expect } from "vitest";
import {
  createEmptyTextSpan,
  createTextSpan,
  getTextSpan,
  getTextSpanLength,
  isTextSpan,
  isTextSpanEnd,
  isTextSpanStart,
  isLikeTextSpan,
  splitTextSpan,
  TAG,
  TYPE,
} from "./TextSpan.js";
import { createLineBreak } from "./LineBreak.js";

/* @vitest-environment jsdom */
describe("TextSpan", () => {
  test("createTextSpan should throw when passed an invalid child", () => {
    expect(() => createTextSpan("Hello, World!")).toThrowError(
      "Invalid textSpan child",
    );
  });

  test("createTextSpan creates a new textSpan element with a <br> inside", () => {
    const textSpan = createTextSpan(createLineBreak());
    expect(textSpan).toBeInstanceOf(HTMLSpanElement);
    expect(textSpan.dataset.itype).toBe(TYPE);
    expect(textSpan.nodeName).toBe(TAG);
    expect(textSpan.textContent).toBe("");
    expect(textSpan.firstChild).toBeInstanceOf(HTMLBRElement);
  });

  test("createTextSpan creates a new textSpan element with a text inside", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    expect(textSpan).toBeInstanceOf(HTMLSpanElement);
    expect(textSpan.dataset.itype).toBe(TYPE);
    expect(textSpan.nodeName).toBe(TAG);
    expect(textSpan.textContent).toBe("Hello, World!");
    expect(textSpan.firstChild).toBeInstanceOf(Text);
  });

  test("createEmptyTextSpan creates a new empty textSpan element with a <br> inside", () => {
    const emptyTextSpan = createEmptyTextSpan();
    expect(emptyTextSpan).toBeInstanceOf(HTMLSpanElement);
    expect(emptyTextSpan.dataset.itype).toBe(TYPE);
    expect(emptyTextSpan.nodeName).toBe(TAG);
    expect(emptyTextSpan.textContent).toBe("");
    expect(emptyTextSpan.firstChild).toBeInstanceOf(HTMLBRElement);
  });

  test("isTextSpan should return true on elements that are text spans", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    expect(isTextSpan(textSpan)).toBe(true);
    const a = document.createElement("a");
    expect(isTextSpan(a)).toBe(false);
    const b = null;
    expect(isTextSpan(b)).toBe(false);
    const c = document.createElement("span");
    expect(isTextSpan(c)).toBe(false);
  });

  test("isLikeTextSpan should return true on elements that have textSpan behavior by default", () => {
    expect(isLikeTextSpan(Infinity)).toBe(false);
    expect(isLikeTextSpan(null)).toBe(false);
    expect(isLikeTextSpan(document.createElement("A"))).toBe(true);
  });

  // FIXME: Should throw?
  test("isTextSpanStart returns false when passed node is not an textSpan", () => {
    const textSpan = document.createElement("div");
    expect(isTextSpanStart(textSpan, 0)).toBe(false);
    expect(isTextSpanStart(textSpan, "Hello, World!".length)).toBe(false);
  });

  test("isTextSpanStart returns if we're at the start of an textSpan", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    expect(isTextSpanStart(textSpan, 0)).toBe(true);
    expect(isTextSpanStart(textSpan, "Hello, World!".length)).toBe(false);
  });

  // FIXME: Should throw?
  test("isTextSpanEnd returns false when passed node is not an textSpan", () => {
    const textSpan = document.createElement("div");
    expect(isTextSpanEnd(textSpan, 0)).toBe(false);
    expect(isTextSpanEnd(textSpan, "Hello, World!".length)).toBe(false);
  });

  test("isTextSpanEnd returns if we're in the end of an textSpan", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    expect(isTextSpanEnd(textSpan, 0)).toBe(false);
    expect(isTextSpanEnd(textSpan, "Hello, World!".length)).toBe(true);
  });

  test("getTextSpan ", () => {
    expect(getTextSpan(null)).toBe(null);
  });

  test("getTextSpanLength throws when the passed node is not an textSpan", () => {
    const textSpan = document.createElement("div");
    expect(() => getTextSpanLength(textSpan)).toThrowError("Invalid textSpan");
  });

  test("getTextSpanLength returns the length of the textSpan content", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    expect(getTextSpanLength(textSpan)).toBe(13);
  });

  test("getTextSpanLength should return 0 when the textSpan content is a <br>", () => {
    const emptyTextSpan = createEmptyTextSpan();
    expect(getTextSpanLength(emptyTextSpan)).toBe(0);
  });

  test("splitTextSpan returns a new textSpan from the splitted textSpan", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    const newTextSpan = splitTextSpan(textSpan, 5);
    expect(newTextSpan).toBeInstanceOf(HTMLSpanElement);
    expect(newTextSpan.firstChild).toBeInstanceOf(Text);
    expect(newTextSpan.textContent).toBe(", World!");
    expect(newTextSpan.dataset.itype).toBe(TYPE);
    expect(newTextSpan.nodeName).toBe(TAG);
  });
});
