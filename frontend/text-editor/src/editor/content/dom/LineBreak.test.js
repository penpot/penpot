import { describe, expect, test } from "vitest";
import { createLineBreak } from "./LineBreak.js";

/* @vitest-environment jsdom */
describe("LineBreak", () => {
  test("createLineBreak should return a <br> element", () => {
    const br = createLineBreak();
    expect(br.nodeType).toBe(Node.ELEMENT_NODE);
    expect(br.nodeName).toBe("BR");
  });
});
