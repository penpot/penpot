import { describe, test, expect, vi } from "vitest";
import {
  getStyles,
  isDisplayBlock,
  isDisplayInline,
  setStyle,
  setStyles,
} from "./Style.js";

/* @vitest-environment jsdom */
describe("Style", () => {
  test("setStyle should apply a style to an element", () => {
    const element = document.createElement("div");
    setStyle(element, "display", "none");
    expect(element.style.display).toBe("none");
  });

  test("setStyles should apply multiple styles to an element using an Object", () => {
    const element = document.createElement("div");
    setStyles(element, [["display"]], {
      "text-decoration": "none",
      "font-size": "32px",
      display: "none",
    });
    expect(element.style.display).toBe("none");
    expect(element.style.fontSize).toBe("");
    expect(element.style.textDecoration).toBe("");
  });

  test("setStyles should apply multiple styles to an element using a CSSStyleDeclaration", () => {
    const a = document.createElement("div");
    setStyles(a, [["display"]], {
      display: "none",
    });
    expect(a.style.display).toBe("none");
    expect(a.style.fontSize).toBe("");
    expect(a.style.textDecoration).toBe("");

    const b = document.createElement("div");
    setStyles(b, [["display"]], a.style);
    expect(b.style.display).toBe("none");
    expect(b.style.fontSize).toBe("");
    expect(b.style.textDecoration).toBe("");
  });

  test("getStyles should retrieve a list of allowed styles", () => {
    const element = document.createElement("div");
    element.style.display = "block";
    element.style.textDecoration = "underline";
    element.style.fontSize = "32px";
    const textDecorationStyles = getStyles(element, [["text-decoration"]]);
    expect(textDecorationStyles).toStrictEqual({
      "text-decoration": "underline",
    });
    const displayStyles = getStyles(element, [["display"]]);
    expect(displayStyles).toStrictEqual({
      display: "block",
    });
    const fontSizeStyles = getStyles(element, [["font-size", "px"]]);
    expect(fontSizeStyles).toStrictEqual({
      "font-size": "32",
    });
  });

  test("isDisplayBlock should return true if display is 'block'", () => {
    const div = document.createElement("div");
    div.style.display = "block";
    expect(isDisplayBlock(div.style)).toBe(true);
    const span = document.createElement("span");
    span.style.display = "inline";
    expect(isDisplayBlock(span)).toBe(false);
  });

  test("isDisplayInline should return true if display is 'inline'", () => {
    const span = document.createElement("span");
    span.style.display = "inline";
    expect(isDisplayInline(span.style)).toBe(true);
    const div = document.createElement("div");
    div.style.display = "block";
    expect(isDisplayInline(div)).toBe(false);
  });
});
