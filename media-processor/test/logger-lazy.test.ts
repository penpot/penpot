import { describe, it, expect, afterEach, vi } from "vitest";

describe("createLogger", () => {
  const original = process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL;

  afterEach(() => {
    if (original === undefined) {
      delete process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL;
    } else {
      process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL = original;
    }
    vi.resetModules();
  });

  it("defers logger initialization until the first log call", async () => {
    vi.resetModules();
    // An invalid level makes loadConfig throw, which is how we observe whether
    // the logger was initialized: it must not be at createLogger time, and must
    // be at the first log call.
    process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL = "definitely-not-a-level";

    const { createLogger } = await import("../src/logger.js");

    const child = createLogger("lazy");
    expect(child).toBeTruthy();

    expect(() => child.info("boom")).toThrow();
  });
});
