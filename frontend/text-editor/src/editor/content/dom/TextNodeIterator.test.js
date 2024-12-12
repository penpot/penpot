import { describe, test, expect } from "vitest";
import TextNodeIterator from "./TextNodeIterator.js";
import { createInline } from "./Inline.js";
import { createParagraph } from "./Paragraph.js";
import { createRoot } from "./Root.js";
import { createLineBreak } from "./LineBreak.js";

/* @vitest-environment jsdom */
describe("TextNodeIterator", () => {
  test("Create a new TextNodeIterator with an invalid root should throw", () => {
    expect(() => new TextNodeIterator(null)).toThrowError("Invalid root node");
    expect(() => new TextNodeIterator(Infinity)).toThrowError(
      "Invalid root node",
    );
    expect(() => new TextNodeIterator(1)).toThrowError("Invalid root node");
    expect(() => new TextNodeIterator("hola")).toThrowError(
      "Invalid root node",
    );
  });

  test("Create a new TextNodeIterator and iterate only over text nodes", () => {
    const rootNode = createRoot([
      createParagraph([
        createInline(new Text("Hello, ")),
        createInline(new Text("World!")),
        createInline(new Text("Whatever")),
      ]),
      createParagraph([createInline(createLineBreak())]),
      createParagraph([
        createInline(new Text("This is a ")),
        createInline(new Text("test")),
      ]),
      createParagraph([createInline(new Text("Hi!"))]),
    ]);

    const textNodeIterator = new TextNodeIterator(rootNode);
    expect(textNodeIterator.currentNode.nodeValue).toBe("Hello, ");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("World!");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Whatever");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeType).toBe(Node.ELEMENT_NODE);
    expect(textNodeIterator.currentNode.nodeName).toBe("BR");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("This is a ");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("test");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Hi!");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("test");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("This is a ");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeType).toBe(Node.ELEMENT_NODE);
    expect(textNodeIterator.currentNode.nodeName).toBe("BR");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Whatever");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("World!");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Hello, ");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("World!");
    textNodeIterator.previousNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Hello, ");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("World!");
    textNodeIterator.nextNode();
    expect(textNodeIterator.currentNode.nodeValue).toBe("Whatever");
  });
});
