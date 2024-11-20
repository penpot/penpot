import { describe, test, expect } from "vitest";
import {
  createElement,
  isElement,
  createRandomId,
  isOffsetAtStart,
  isOffsetAtEnd,
} from "./Element.js";

/* @vitest-environment jsdom */
describe("Element", () => {
  test("createRandomId should create a new random id", () => {
    const randomId = createRandomId();
    expect(typeof randomId).toBe("string");
    expect(randomId.length).toBeGreaterThan(0);
    expect(randomId.length).toBeLessThan(12);
  });

  test("createElement should create a new element", () => {
    const element = createElement("div");
    expect(element.nodeType).toBe(Node.ELEMENT_NODE);
    expect(element.nodeName).toBe("DIV");
  });

  test("createElement should create a new element with attributes", () => {
    const element = createElement("div", {
      attributes: {
        "aria-multiline": true,
        role: "textbox",
      },
    });
    expect(element.ariaMultiLine).toBe("true");
    expect(element.role).toBe("textbox");
  });

  test("createElement should create a new element with data- properties", () => {
    const element = createElement("div", {
      data: {
        itype: "root",
      },
    });
    expect(element.dataset.itype).toBe("root");
  });

  test("createElement should create a new element with styles from an object", () => {
    const element = createElement("div", {
      styles: {
        "text-decoration": "underline",
      },
      allowedStyles: [["text-decoration"]],
    });
    expect(element.style.textDecoration).toBe("underline");
  });

  test("createElement should create a new element with a child", () => {
    const element = createElement("div", {
      children: new Text("Hello, World!"),
    });
    expect(element.textContent).toBe("Hello, World!");
  });

  test("createElement should create a new element with children", () => {
    const element = createElement("div", {
      children: [
        createElement("div", {
          children: [
            createElement("div", {
              children: new Text("Hello, World!"),
            }),
          ],
        }),
      ],
    });
    expect(element.textContent).toBe("Hello, World!");
    expect(element.firstChild.nodeType).toBe(Node.ELEMENT_NODE);
    expect(element.firstChild.firstChild.nodeType).toBe(Node.ELEMENT_NODE);
    expect(element.firstChild.firstChild.firstChild.nodeType).toBe(
      Node.TEXT_NODE,
    );
  });

  test("isElement returns true if the passed element is the expected element", () => {
    const br = createElement("br");
    expect(isElement(br, "br")).toBe(true);
    const div = createElement("div");
    expect(isElement(div, "div")).toBe(true);
    const text = new Text("Hello, World!");
    expect(isElement(text, "text")).toBe(false);
  });

  test("isOffsetAtStart should return true when offset is 0", () => {
    const element = createElement("span", {
      children: new Text("Hello"),
    });
    expect(isOffsetAtStart(element, 0)).toBe(true);
  });

  test("isOffsetAtEnd should return true when offset is the length of the text content", () => {
    const element = createElement("span", {
      children: new Text("Hello"),
    });
    expect(isOffsetAtEnd(element, 5)).toBe(true);
  });

  test("isOffsetAtEnd should return true when the node is a Text and offset is the length of the node", () => {
    const element = new Text("Hello");
    expect(isOffsetAtEnd(element, 5)).toBe(true);
  });

  test("isOffsetAtEnd should return true when node is an element", () => {
    const element = createElement("span", {
      children: createElement("br"),
    });
    expect(isOffsetAtEnd(element, 5)).toBe(true);
  });
});
