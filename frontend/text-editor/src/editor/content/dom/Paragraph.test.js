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
  createParagraphWith,
} from "./Paragraph.js";
import { createTextSpan, isTextSpan } from "./TextSpan.js";
import { isLineBreak } from './LineBreak.js';
import { isTextNode } from './TextNode.js';

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
    expect(isTextSpan(emptyParagraph.firstChild)).toBeTruthy();
    expect(isLineBreak(emptyParagraph.firstChild.firstChild)).toBeTruthy();
  });

  test("createParagraphWith should create a new paragraph with text", () => {
    // "" as empty paragraph.
    {
      const emptyParagraph = createParagraphWith("");
      expect(emptyParagraph).toBeInstanceOf(HTMLDivElement);
      expect(emptyParagraph.nodeName).toBe(TAG);
      expect(emptyParagraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(emptyParagraph.firstChild)).toBeTruthy();
      expect(isLineBreak(emptyParagraph.firstChild.firstChild)).toBeTruthy();
    }
    // "\n" as empty paragraph.
    {
      const emptyParagraph = createParagraphWith("\n");
      expect(emptyParagraph).toBeInstanceOf(HTMLDivElement);
      expect(emptyParagraph.nodeName).toBe(TAG);
      expect(emptyParagraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(emptyParagraph.firstChild)).toBeTruthy();
      expect(isLineBreak(emptyParagraph.firstChild.firstChild)).toBeTruthy();
    }
    // [""] as empty paragraph.
    {
      const emptyParagraph = createParagraphWith([""]);
      expect(emptyParagraph).toBeInstanceOf(HTMLDivElement);
      expect(emptyParagraph.nodeName).toBe(TAG);
      expect(emptyParagraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(emptyParagraph.firstChild)).toBeTruthy();
      expect(isLineBreak(emptyParagraph.firstChild.firstChild)).toBeTruthy();
    }
    // ["\n"] as empty paragraph.
    {
      const emptyParagraph = createParagraphWith(["\n"]);
      expect(emptyParagraph).toBeInstanceOf(HTMLDivElement);
      expect(emptyParagraph.nodeName).toBe(TAG);
      expect(emptyParagraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(emptyParagraph.firstChild)).toBeTruthy();
      expect(isLineBreak(emptyParagraph.firstChild.firstChild)).toBeTruthy();
    }
    // "Lorem ipsum" as a paragraph with a text span.
    {
      const paragraph = createParagraphWith("Lorem ipsum");
      expect(paragraph).toBeInstanceOf(HTMLDivElement);
      expect(paragraph.nodeName).toBe(TAG);
      expect(paragraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(paragraph.firstChild)).toBeTruthy();
      expect(isTextNode(paragraph.firstChild.firstChild)).toBeTruthy();
      expect(paragraph.firstChild.firstChild.textContent).toBe("Lorem ipsum");
    }
    // ["Lorem ipsum"] as a paragraph with a text span.
    {
      const paragraph = createParagraphWith(["Lorem ipsum"]);
      expect(paragraph).toBeInstanceOf(HTMLDivElement);
      expect(paragraph.nodeName).toBe(TAG);
      expect(paragraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(paragraph.firstChild)).toBeTruthy();
      expect(isTextNode(paragraph.firstChild.firstChild)).toBeTruthy();
      expect(paragraph.firstChild.firstChild.textContent).toBe("Lorem ipsum");
    }
    // ["Lorem ipsum","\n","dolor sit amet"] as a paragraph with multiple text spans.
    {
      const paragraph = createParagraphWith(["Lorem ipsum", "\n", "dolor sit amet"]);
      expect(paragraph).toBeInstanceOf(HTMLDivElement);
      expect(paragraph.nodeName).toBe(TAG);
      expect(paragraph.dataset.itype).toBe(TYPE);
      expect(isTextSpan(paragraph.children.item(0))).toBeTruthy();
      expect(isTextNode(paragraph.children.item(0).firstChild)).toBeTruthy();
      expect(paragraph.children.item(0).firstChild.textContent).toBe("Lorem ipsum");
      expect(isTextSpan(paragraph.children.item(1))).toBeTruthy();
      expect(isLineBreak(paragraph.children.item(1).firstChild)).toBeTruthy();
      expect(isTextSpan(paragraph.children.item(2))).toBeTruthy();
      expect(isTextNode(paragraph.children.item(2).firstChild)).toBeTruthy();
      expect(paragraph.children.item(2).firstChild.textContent).toBe("dolor sit amet");
    }
    {
      expect(() => {
        createParagraphWith({});
      }).toThrow("Invalid text, it should be an array of strings or a string");
    }
  })

  test("isParagraph should return true when the passed node is a paragraph", () => {
    expect(isParagraph(null)).toBeFalsy();
    expect(isParagraph(document.createElement("div"))).toBeFalsy();
    expect(isParagraph(document.createElement("h1"))).toBeFalsy();
    expect(isParagraph(createEmptyParagraph())).toBeTruthy();
    expect(
      isParagraph(createParagraph([createTextSpan(new Text("Hello, World!"))])),
    ).toBeTruthy();
  });

  test("isLikeParagraph should return true when node looks like a paragraph", () => {
    const p = document.createElement("p");
    expect(isLikeParagraph(p)).toBeTruthy();
    const div = document.createElement("div");
    expect(isLikeParagraph(div)).toBeTruthy();
    const h1 = document.createElement("h1");
    expect(isLikeParagraph(h1)).toBeTruthy();
    const h2 = document.createElement("h2");
    expect(isLikeParagraph(h2)).toBeTruthy();
    const h3 = document.createElement("h3");
    expect(isLikeParagraph(h3)).toBeTruthy();
    const h4 = document.createElement("h4");
    expect(isLikeParagraph(h4)).toBeTruthy();
    const h5 = document.createElement("h5");
    expect(isLikeParagraph(h5)).toBeTruthy();
    const h6 = document.createElement("h6");
    expect(isLikeParagraph(h6)).toBeTruthy();
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
    expect(isParagraphStart(paragraph.firstChild.firstChild, 0)).toBeTruthy();
  });

  test("isParagraphStart should return true on a paragraph", () => {
    const paragraph = createParagraph([
      createTextSpan(new Text("Hello, World!")),
    ]);
    expect(isParagraphStart(paragraph.firstChild.firstChild, 0)).toBeTruthy();
  });

  test("isParagraphEnd should return true on an empty paragraph", () => {
    const paragraph = createEmptyParagraph();
    expect(isParagraphEnd(paragraph.firstElementChild.firstChild, 0)).toBeTruthy();
  });

  test("isParagraphEnd should return true on a paragraph", () => {
    const paragraph = createParagraph([
      createTextSpan(new Text("Hello, World!")),
    ]);
    expect(isParagraphEnd(paragraph.firstElementChild.firstChild, 13)).toBeTruthy();
  });

  test("isParagraphEnd should return false on a paragrah where the focus offset is inside", () => {
    const paragraph = createParagraph([
      createTextSpan(new Text("Lorem ipsum sit")),
      createTextSpan(new Text("amet")),
    ]);
    expect(isParagraphEnd(paragraph.firstElementChild.firstChild, 15)).toBeFalsy();
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
    const paragraph = createParagraph([
      helloTextSpan,
      worldTextSpan,
      exclTextSpan,
    ]);
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
    expect(isLikeParagraph(span)).toBeFalsy();
    expect(isLikeParagraph(a)).toBeFalsy();
    expect(isLikeParagraph(br)).toBeFalsy();
    expect(isLikeParagraph(i)).toBeFalsy();
    expect(isLikeParagraph(u)).toBeFalsy();
    expect(isLikeParagraph(div)).toBeTruthy();
    expect(isLikeParagraph(blockquote)).toBeTruthy();
    expect(isLikeParagraph(table)).toBeTruthy();
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
    expect(isEmptyParagraph(emptyParagraph)).toBeTruthy();

    const nonEmptyTextSpan = document.createElement("span");
    nonEmptyTextSpan.dataset.itype = "span";
    nonEmptyTextSpan.appendChild(new Text("Not empty!"));
    const nonEmptyParagraph = document.createElement("div");
    nonEmptyParagraph.dataset.itype = "paragraph";
    nonEmptyParagraph.appendChild(nonEmptyTextSpan);
    expect(isEmptyParagraph(nonEmptyParagraph)).toBeFalsy();
  });
});
