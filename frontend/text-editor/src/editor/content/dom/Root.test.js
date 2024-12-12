import { describe, test, expect } from "vitest";
import { createEmptyRoot, createRoot, setRootStyles, TAG, TYPE } from "./Root.js";

/* @vitest-environment jsdom */
describe("Root", () => {
  test("createRoot should throw when passed invalid children", () => {
    expect(() => createRoot(["Whatever"])).toThrowError(
      "Invalid root children",
    );
  });

  test("createEmptyRoot should create a new root with an empty paragraph", () => {
    const emptyRoot = createEmptyRoot();
    expect(emptyRoot).toBeInstanceOf(HTMLDivElement);
    expect(emptyRoot.nodeName).toBe(TAG);
    expect(emptyRoot.dataset.itype).toBe(TYPE);
    expect(emptyRoot.firstChild).toBeInstanceOf(HTMLDivElement);
    expect(emptyRoot.firstChild.firstChild).toBeInstanceOf(HTMLSpanElement);
    expect(emptyRoot.firstChild.firstChild.firstChild).toBeInstanceOf(
      HTMLBRElement,
    );
  });

  test("setRootStyles should apply only the styles of root to the root", () => {
    const emptyRoot = createEmptyRoot();
    setRootStyles(emptyRoot, {
      ["--vertical-align"]: "top",
      ["font-size"]: "25px",
    });
    expect(emptyRoot.style.getPropertyValue("--vertical-align")).toBe("top");
    // We expect this style to be empty because we don't apply it
    // to the root.
    expect(emptyRoot.style.getPropertyValue("font-size")).toBe("");
  });
});
