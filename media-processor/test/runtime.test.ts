import { afterEach, describe, expect, it } from "vitest";
import { configureRuntime } from "../src/runtime.js";
import { configureFontLimits, getFontLimits } from "../src/services/font.js";
import { configureImageLimits, getImageLimits } from "../src/services/image.js";

const defaults = {
  image: { maxPixels: 128_000_000, maxWidth: 16384, maxHeight: 16384 },
  font: { mem: 512, cpuTime: 30, timeout: 120_000 },
};

afterEach(() => {
  configureImageLimits(defaults.image);
  configureFontLimits(defaults.font);
});

describe("configureRuntime", () => {
  it("applies the image and font limits from the config", () => {
    configureRuntime({
      imageMaxPixels: 1_000,
      imageMaxWidth: 100,
      imageMaxHeight: 50,
      fontProcessMem: 256,
      fontProcessCpuTime: 10,
      fontTimeout: 30_000,
    });

    expect(getImageLimits()).toEqual({ maxPixels: 1_000, maxWidth: 100, maxHeight: 50 });
    expect(getFontLimits()).toEqual({ mem: 256, cpuTime: 10, timeout: 30_000 });
  });
});
