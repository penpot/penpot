import { describe, test, expect } from "vitest";
import { insertInto, removeSlice, removeBackward, removeForward, removeWordBackward, replaceWith, findPreviousWordBoundary } from "./Text";

describe("Text", () => {
  test("* should throw when passed wrong parameters", () => {
    expect(() => insertInto(Infinity, Infinity, Infinity)).toThrowError(
      "Invalid string",
    );
    expect(() => insertInto("Hello", Infinity, Infinity)).toThrowError(
      "Invalid offset",
    );
    expect(() => insertInto("Hello", 0, Infinity)).toThrowError(
      "Invalid string",
    );
  });

  test("`insertInto` should insert a string into an offset", () => {
    expect(insertInto("Hell, World!", 4, "o")).toBe("Hello, World!");
  });

  test("`replaceWith` should replace a string into a string", () => {
    expect(replaceWith("Hello, Something!", 7, 16, "World")).toBe(
      "Hello, World!",
    );
  });

  test("`removeBackward` should remove string backward from start (offset 0)", () => {
    expect(removeBackward("Hello, World!", 0)).toBe("Hello, World!");
  });

  test("`removeForward` should remove string forward from start (offset 0)", () => {
    expect(removeForward("Hello, World!", 0)).toBe("ello, World!");
  });

  test("`removeBackward` should remove string backward from end", () => {
    expect(removeBackward("Hello, World!", "Hello, World!".length)).toBe(
      "Hello, World",
    );
  });

  test("`removeForward` should remove string forward from end", () => {
    expect(removeForward("Hello, World!", "Hello, World!".length)).toBe(
      "Hello, World!",
    );
  });

  test("`removeBackward` should remove string backward from offset 6", () => {
    expect(removeBackward("Hello, World!", 6)).toBe("Hello World!");
  });

  test("`removeForward` should remove string forward from offset 6", () => {
    expect(removeForward("Hello, World!", 6)).toBe("Hello,World!");
  });

  test("`removeSlice` should remove a part of a text", () => {
    expect(removeSlice("Hello, World!", 7, 12)).toBe("Hello, !");
  });

  test("`findPreviousWordBoundary` edge cases", () => {
    expect(findPreviousWordBoundary(null)).toBe(0);
    expect(findPreviousWordBoundary("Hello, World!", 0)).toBe(0);
    expect(findPreviousWordBoundary("   Hello, World!", 3)).toBe(0);
  })

  test("`removeWordBackward` with no text should return an empty string", () => {
    expect(removeWordBackward(null, 0)).toBe("");
  });

  test("`removeWordBackward` should remove a word backward", () => {
    expect(removeWordBackward("Hello, World!", 13)).toBe("Hello, World");
    expect(removeWordBackward("Hello, World", 12)).toBe("Hello, ");
  });
});
