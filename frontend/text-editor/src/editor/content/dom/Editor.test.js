import { describe, test, expect } from "vitest";
import {
  isEditor,
  TYPE,
  TAG,
} from "./Editor.js";

/* @vitest-environment jsdom */
describe("Editor", () => {
  test("isEditor should return true", () => {
    const element = document.createElement(TAG)
    element.dataset.itype = TYPE;
    expect(isEditor(element)).toBeTruthy();
  });

  test("isEditor should return false when element is null", () => {
    expect(isEditor(null)).toBeFalsy();
  });

  test("isEditor should return false when the tag is not valid", () => {
    const element = document.createElement("span");
    expect(isEditor(element)).toBeFalsy();
  });

  test("isEditor should return false when the itype is not valid", () => {
    const element = document.createElement(TAG);
    element.dataset.itype = "whatever";
    expect(isEditor(element)).toBeFalsy();
  });
});
