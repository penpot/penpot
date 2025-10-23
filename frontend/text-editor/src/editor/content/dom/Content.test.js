import { describe, test, expect } from "vitest";
import {
  mapContentFragmentFromHTML,
  mapContentFragmentFromString,
} from "./Content.js";

/* @vitest-environment jsdom */
describe("Content", () => {
  test("mapContentFragmentFromHTML should return a valid content for the editor", () => {
    const inertElement = document.createElement("div");
    const contentFragment = mapContentFragmentFromHTML(
      "<div>Hello, World!</div>",
      inertElement.style,
    );
    expect(contentFragment).toBeInstanceOf(DocumentFragment);
    expect(contentFragment.children).toHaveLength(1);
    expect(contentFragment.firstElementChild).toBeInstanceOf(HTMLDivElement);
    expect(contentFragment.firstElementChild.firstElementChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(
      contentFragment.firstElementChild.firstElementChild.firstChild,
    ).toBeInstanceOf(Text);
    expect(contentFragment.textContent).toBe("Hello, World!");
  });

  test("mapContentFragmentFromHTML should return a valid content for the editor (multiple text spans)", () => {
    const inertElement = document.createElement("div");
    const contentFragment = mapContentFragmentFromHTML(
      "<div>Hello,<br/><span> World!</span><br/></div>",
      inertElement.style,
    );
    expect(contentFragment).toBeInstanceOf(DocumentFragment);
    expect(contentFragment.children).toHaveLength(1);
    expect(contentFragment.firstElementChild).toBeInstanceOf(HTMLDivElement);
    expect(contentFragment.firstElementChild.children).toHaveLength(2);
    expect(contentFragment.firstElementChild.firstElementChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(
      contentFragment.firstElementChild.firstElementChild.firstChild,
    ).toBeInstanceOf(Text);
    expect(contentFragment.textContent).toBe("Hello, World!");
  });

  test("mapContentFragmentFromHTML should return a valid content for the editor (multiple paragraphs)", () => {
    const paragraphs = [
      "Lorem ipsum",
      "Dolor sit amet",
      "Sed iaculis blandit odio ornare sagittis.",
    ];
    const inertElement = document.createElement("div");
    const contentFragment = mapContentFragmentFromHTML(
      "<div>Lorem ipsum</div><div>Dolor sit amet</div><div><br/></div><div>Sed iaculis blandit odio ornare sagittis.</div>",
      inertElement.style,
    );
    expect(contentFragment).toBeInstanceOf(DocumentFragment);
    expect(contentFragment.children).toHaveLength(3);
    for (let index = 0; index < contentFragment.children.length; index++) {
      expect(contentFragment.children.item(index)).toBeInstanceOf(
        HTMLDivElement,
      );
      expect(
        contentFragment.children.item(index).firstElementChild,
      ).toBeInstanceOf(HTMLSpanElement);
      expect(
        contentFragment.children.item(index).firstElementChild.firstChild,
      ).toBeInstanceOf(Text);
      expect(contentFragment.children.item(index).textContent).toBe(
        paragraphs[index],
      );
    }
    expect(contentFragment.textContent).toBe(
      "Lorem ipsumDolor sit ametSed iaculis blandit odio ornare sagittis.",
    );
  });

  test("mapContentFragmentFromString should return a valid content for the editor", () => {
    const contentFragment = mapContentFragmentFromString("Hello, \nWorld!");
    expect(contentFragment).toBeInstanceOf(DocumentFragment);
    expect(contentFragment.children).toHaveLength(2);
    expect(contentFragment.children.item(0)).toBeInstanceOf(HTMLDivElement);
    expect(contentFragment.children.item(1)).toBeInstanceOf(HTMLDivElement);
    expect(contentFragment.children.item(0).firstElementChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(
      contentFragment.children.item(0).firstElementChild.firstChild,
    ).toBeInstanceOf(Text);
    expect(contentFragment.children.item(1).firstElementChild).toBeInstanceOf(
      HTMLSpanElement,
    );
    expect(
      contentFragment.children.item(1).firstElementChild.firstChild,
    ).toBeInstanceOf(Text);
    expect(contentFragment.textContent).toBe("Hello, World!");
  });
});
