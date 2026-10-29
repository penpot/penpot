import sharp from "sharp";
import type { AppConfig } from "./types.js";
import { configureFontLimits } from "./services/font.js";
import { configureImageLimits } from "./services/image.js";

export type RuntimeConfig = Pick<
  AppConfig,
  "imageMaxPixels" | "imageMaxWidth" | "imageMaxHeight" | "fontProcessMem" | "fontProcessCpuTime" | "fontTimeout"
>;

/**
 * Wire the resource limits that live in the service modules. Kept out of
 * `index.ts` so a test can apply a config and read it back without starting the
 * process.
 */
export function configureRuntime(config: RuntimeConfig): void {
  // libvips keeps a per-thread cache; a long-lived worker must not grow with it.
  sharp.cache(false);
  configureImageLimits({
    maxPixels: config.imageMaxPixels,
    maxWidth: config.imageMaxWidth,
    maxHeight: config.imageMaxHeight,
  });
  configureFontLimits({
    mem: config.fontProcessMem,
    cpuTime: config.fontProcessCpuTime,
    timeout: config.fontTimeout,
  });
}
