import { describe, test, expect } from "vitest";
import { StyleDeclaration } from "./StyleDeclaration.js";

describe("StyleDeclaration", () => {
  test("Create a new StyleDeclaration", () => {
    const styleDeclaration = new StyleDeclaration();
    expect(styleDeclaration).toBeInstanceOf(StyleDeclaration);
  });

  test("Uninmplemented getters should throw", () => {
    expect(() => styleDeclaration.cssFloat).toThrow();
    expect(() => styleDeclaration.cssText).toThrow();
    expect(() => styleDeclaration.parentRule).toThrow();
  });

  test("Set property", () => {
    const styleDeclaration = new StyleDeclaration();
    styleDeclaration.setProperty("line-height", "1.2");
    expect(styleDeclaration.getPropertyValue("line-height")).toBe("1.2");
    expect(styleDeclaration.getPropertyPriority("line-height")).toBe("");
  });

  test("Remove property", () => {
    const styleDeclaration = new StyleDeclaration();
    styleDeclaration.setProperty("line-height", "1.2");
    expect(styleDeclaration.getPropertyValue("line-height")).toBe("1.2");
    expect(styleDeclaration.getPropertyPriority("line-height")).toBe("");
    styleDeclaration.removeProperty("line-height");
    expect(styleDeclaration.getPropertyValue("line-height")).toBe("");
    expect(styleDeclaration.getPropertyPriority("line-height")).toBe("");
  });
});
