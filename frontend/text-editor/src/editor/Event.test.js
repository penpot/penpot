import { describe, test, expect, vi } from "vitest";
import { addEventListeners, removeEventListeners } from "./Event.js";

/* @vitest-environment jsdom */
describe("Event", () => {
  test("addEventListeners should add event listeners to an element using an object", () => {
    const clickSpy = vi.fn();
    const events = {
      click: clickSpy,
    };
    const element = document.createElement("div");
    addEventListeners(element, events);
    element.dispatchEvent(new Event("click"));
    expect(clickSpy).toBeCalled();
  });

  test("removeEventListeners should remove event listeners to an element using an object", () => {
    const clickSpy = vi.fn();
    const events = {
      click: clickSpy,
    };
    const element = document.createElement("div");
    addEventListeners(element, events);
    element.dispatchEvent(new Event("click"));
    removeEventListeners(element, events);
    element.dispatchEvent(new Event("click"));
    expect(clickSpy).toBeCalledTimes(1);
  });
});
