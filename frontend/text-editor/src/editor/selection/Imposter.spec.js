import { expect, test } from "vitest";
import { createSelectionImposterFromClientRects } from "./Imposter.js";

/* @vitest-environment jsdom */
test("Create selection DOM rects from client rects", () => {
  const rect = new DOMRect(20, 20, 100, 50);
  const clientRects = [
    new DOMRect(20, 20, 100, 20),
    new DOMRect(20, 50, 50, 20),
  ];
  const fragment = createSelectionImposterFromClientRects(rect, clientRects);
  expect(fragment).toBeInstanceOf(DocumentFragment);
  expect(fragment.childNodes).toHaveLength(2);
});
