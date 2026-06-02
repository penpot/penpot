import { expect, describe, test, vi } from "vitest";
import ChangeController from "./ChangeController.js";

describe("ChangeController", () => {
  test("Creating a ChangeController without a valid time should throw", () => {
    expect(() => new ChangeController(Infinity)).toThrowError("Invalid time");
  });

  test("A ChangeController should dispatch an event when `notifyImmediately` is called", () => {
    const changeListener = vi.fn();
    const changeController = new ChangeController(10);
    changeController.addEventListener("change", changeListener);
    changeController.notifyImmediately();
    expect(changeController.hasPendingChanges).toBe(false);
    expect(changeListener).toBeCalled(1);
  });

  test("A ChangeController should dispatch an event when `notifyDebounced` is called", async () => {
    return new Promise((resolve) => {
      const changeController = new ChangeController(10);
      changeController.addEventListener("change", () => resolve());
      changeController.notifyDebounced();
      expect(changeController.hasPendingChanges).toBe(true);
    });
  });

  test("A ChangeController should dispatch an event when `notifyDebounced` is called and disposed is called right after", async () => {
    return new Promise((resolve) => {
      const changeController = new ChangeController(10);
      changeController.addEventListener("change", () => resolve());
      changeController.notifyDebounced();
      expect(changeController.hasPendingChanges).toBe(true);
      changeController.dispose();
    });
  });
});
