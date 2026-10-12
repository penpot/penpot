import { loadConfig } from "./config.js";
import { initLogger, logger, logActiveTransports } from "./logger.js";
import { createApp } from "./app.js";
import { configureImageLimits } from "./services/image.js";
import { configureFontLimits } from "./services/font.js";
import { configureSvgLimits } from "./services/svg.js";
import { configureSvgPool, shutdownSvgPool } from "./svg-pool.js";
import { configureUploadLimits } from "./upload.js";
import sharp from "sharp";

// Auth is enforced via x-shared-key header (sharedKeyAuth middleware).
// When no key is configured, all requests are rejected (403).
// This service MUST be deployed on an internal Docker network only
// — do NOT expose to the public internet.

// Disable sharp/libvips caching to prevent unbounded memory growth
sharp.cache(false);

const config = loadConfig();
initLogger(config);

// Configure resource limits
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

configureSvgLimits({ maxSize: config.svgMaxSize });

configureSvgPool({
  workers: config.svgWorkers,
  maxOldGenerationSizeMb: config.svgWorkerMaxOldMb,
  timeout: config.svgTimeout,
});

configureUploadLimits({ maxFileSize: config.maxFileSize, memoryThreshold: config.memoryThreshold });

const app = createApp(config);

app.listen(config.port, config.host, () => {
  logActiveTransports(logger);
  logger.info(`media-processor listening on ${config.host}:${config.port}`);
});

// Terminate the SVG workers on shutdown so in-flight sanitizations fail fast
// instead of keeping the pool's timers alive until the process dies.
for (const signal of ["SIGTERM", "SIGINT"] as const) {
  process.on(signal, () => {
    void shutdownSvgPool().finally(() => process.exit(0));
  });
}

export { app };
