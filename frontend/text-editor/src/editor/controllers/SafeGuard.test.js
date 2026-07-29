import { describe, test, expect } from "vitest";
import { SafeGuard } from "./SafeGuard.js";

describe("SafeGuard", () => {
  test("create a new SafeGuard", () => {
    const safeGuard = new SafeGuard("Context");
    expect(safeGuard.context).toBe("Context");
    expect(safeGuard.elapsed).toBeLessThan(100);
  });

  test("SafeGuard throws an error when too much time is spent", () => {
    expect(() => {
      const safeGuard = new SafeGuard("Context", 100);
      safeGuard.start();
      // NOTE: This is the type of loop we try to
      // be safe.
      while (true) {
        safeGuard.update();
      }
    }).toThrow('Safe guard timeout "Context"');
  });
});
