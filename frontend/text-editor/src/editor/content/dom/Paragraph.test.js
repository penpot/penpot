import { describe, test, expect } from "vitest";
import {
  createEmptyParagraph,
  createParagraph,
  getParagraph,
  isLikeParagraph,
  isParagraph,
  isParagraphStart,
  isParagraphEnd,
  TAG,
  TYPE,
  splitParagraph,
  splitParagraphAtNode,
  isEmptyParagraph,
} from "./Paragraph.js";
import { createTextSpan, isTextSpan } from "./TextSpan.js";

/* @vitest-environment jsdom */
describe("Paragraph", () => {
  test("createParagraph should throw when passed invalid children", () => {
    expect(() => createParagraph(["Whatever"])).toThrowError(
      "Invalid paragraph children",
    );
  });

  test("createEmptyParagraph should create a new empty paragraph", () => {
    const emptyParagraph = createEmptyParagraph();
    expect(emptyParagraph).toBeInstanceOf(HTMLDivElement);
    expect(emptyParagraph.nodeName).toBe(TAG);
    expect(emptyParagraph.dataset.itype).toBe(TYPE);
    expect(isTextSpan(emptyParagraph.firstChild)).toBe(true);
  });

  test("isParagraph should return true when the passed node is a paragraph", () => {
    expect(isParagraph(null)).toBe(false);
    expect(isParagraph(document.createElement("div"))).toBe(false);
    expect(isParagraph(document.createElement("h1"))).toBe(false);
    expect(isParagraph(createEmptyParagraph())).toBe(true);
    expect(
      isParagraph(createParagraph([createTextSpan(new Text("Hello, World!"))])),
    ).toBe(true);
  });

  test("isLikeParagraph should return true when node looks like a paragraph", () => {
    const p = document.createElement("p");
    expect(isLikeParagraph(p)).toBe(true);
    const div = document.createElement("div");
    expect(isLikeParagraph(div)).toBe(true);
    const h1 = document.createElement("h1");
    expect(isLikeParagraph(h1)).toBe(true);
    const h2 = document.createElement("h2");
    expect(isLikeParagraph(h2)).toBe(true);
    const h3 = document.createElement("h3");
    expect(isLikeParagraph(h3)).toBe(true);
    const h4 = document.createElement("h4");
    expect(isLikeParagraph(h4)).toBe(true);
    const h5 = document.createElement("h5");
    expect(isLikeParagraph(h5)).toBe(true);
    const h6 = document.createElement("h6");
    expect(isLikeParagraph(h6)).toBe(true);
  });

  test("getParagraph should return the closest paragraph of the passed node", () => {
    const text = new Text("Hello, World!");
    const textSpan = createTextSpan(text);
    const paragraph = createParagraph([textSpan]);
    expect(getParagraph(text)).toBe(paragraph);
  });

  test("getParagraph should return null if there aren't closer paragraph nodes", () => {
    const text = new Text("Hello, World!");
    const whatever = document.createElement("div");
    whatever.appendChild(text);
    expect(getParagraph(text)).toBe(null);
  });

  test("isParagraphStart should return true on an empty paragraph", () => {
    const paragraph = createEmptyParagraph();
    expect(isParagraphStart(paragraph.firstChild.firstChild, 0)).toBe(true);
  });

  test("isParagraphStart should return true on a paragraph", () => {
    const paragraph = createParagraph([
      createTextSpan(new Text("Hello, World!")),
    ]);
    expect(isParagraphStart(paragraph.firstChild.firstChild, 0)).toBe(true);
  });

  test("isParagraphEnd should return true on an empty paragraph", () => {
    const paragraph = createEmptyParagraph();
    expect(isParagraphEnd(paragraph.firstChild.firstChild, 0)).toBe(true);
  });

  test("isParagraphEnd should return true on a paragraph", () => {
    const paragraph = createParagraph([
      createTextSpan(new Text("Hello, World!")),
    ]);
    expect(isParagraphEnd(paragraph.firstChild.firstChild, 13)).toBe(true);
  });

  test("splitParagraph should split a paragraph", () => {
    const textSpan = createTextSpan(new Text("Hello, World!"));
    const paragraph = createParagraph([textSpan]);
    const newParagraph = splitParagraph(paragraph, textSpan, 6);
    expect(newParagraph).toBeInstanceOf(HTMLDivElement);
    expect(newParagraph.nodeName).toBe(TAG);
    expect(newParagraph.dataset.itype).toBe(TYPE);
    expect(newParagraph.firstElementChild.textContent).toBe(" World!");
  });

  test("splitParagraphAtNode should split a paragraph at a specified node", () => {
    const helloTextSpan = createTextSpan(new Text("Hello, "));
    const worldTextSpan = createTextSpan(new Text("World"));
    const exclTextSpan = createTextSpan(new Text("!"));
    const paragraph = createParagraph([helloTextSpan, worldTextSpan, exclTextSpan]);
    const newParagraph = splitParagraphAtNode(paragraph, 1);
    expect(newParagraph).toBeInstanceOf(HTMLDivElement);
    expect(newParagraph.nodeName).toBe(TAG);
    expect(newParagraph.dataset.itype).toBe(TYPE);
    expect(newParagraph.children.length).toBe(2);
    expect(newParagraph.textContent).toBe("World!");
  });

  test("isLikeParagraph should return true if the element it's not an text span element", () => {
    const span = document.createElement("span");
    const a = document.createElement("a");
    const br = document.createElement("br");
    const i = document.createElement("span");
    const u = document.createElement("span");
    const div = document.createElement("div");
    const blockquote = document.createElement("blockquote");
    const table = document.createElement("table");
    expect(isLikeParagraph(span)).toBe(false);
    expect(isLikeParagraph(a)).toBe(false);
    expect(isLikeParagraph(br)).toBe(false);
    expect(isLikeParagraph(i)).toBe(false);
    expect(isLikeParagraph(u)).toBe(false);
    expect(isLikeParagraph(div)).toBe(true);
    expect(isLikeParagraph(blockquote)).toBe(true);
    expect(isLikeParagraph(table)).toBe(true);
  });

  test("isEmptyParagraph should return true if the paragraph is empty", () => {
    expect(() => {
      isEmptyParagraph(document.createElement("svg"));
    }).toThrowError("Invalid paragraph");
    expect(() => {
      const paragraph = document.createElement("div");
      paragraph.dataset.itype = "paragraph";
      paragraph.appendChild(document.createElement("svg"));
      isEmptyParagraph(paragraph);
    }).toThrowError("Invalid text span");

    const lineBreak = document.createElement("br");
    const emptyTextSpan = document.createElement("span");
    emptyTextSpan.dataset.itype = "span";
    emptyTextSpan.appendChild(lineBreak);
    const emptyParagraph = document.createElement("div");
    emptyParagraph.dataset.itype = "paragraph";
    emptyParagraph.appendChild(emptyTextSpan);
    expect(isEmptyParagraph(emptyParagraph)).toBe(true);

    const nonEmptyTextSpan = document.createElement("span");
    nonEmptyTextSpan.dataset.itype = "span";
    nonEmptyTextSpan.appendChild(new Text("Not empty!"));
    const nonEmptyParagraph = document.createElement("div");
    nonEmptyParagraph.dataset.itype = "paragraph";
    nonEmptyParagraph.appendChild(nonEmptyTextSpan);
    expect(isEmptyParagraph(nonEmptyParagraph)).toBe(false);
  });
});
