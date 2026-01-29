import { describe, test, expect } from "vitest";
import { getFills } from "./Color.js";

/* @vitest-environment jsdom */
describe.skip("Color", () => {
  test("getFills", () => {
    expect(getFills("#aa0000")).toBe(
      '[["^ ","~:fill-color","#aa0000","~:fill-opacity",1]]',
    );
  });
});
