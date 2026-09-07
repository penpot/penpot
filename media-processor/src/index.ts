import express, { type Express } from "express";
import { loadConfig } from "./config.js";
import { initLogger, logger, logActiveTransports } from "./logger.js";
import { healthRoutes } from "./routes/health.js";
import { createImageRoutes } from "./routes/image.js";
import { createFontRoutes } from "./routes/font.js";
import { errorHandler } from "./middleware/error-handler.js";
import { timeoutMiddleware } from "./middleware/timeout.js";
import { sharedKeyAuth } from "./middleware/auth.js";
import { createQueueMiddleware } from "./middleware/queue.js";
import { loggingMiddleware } from "./middleware/logging.js";
import { configureImageLimits } from "./services/image.js";
import { configureFontLimits } from "./services/font.js";
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
const app: Express = express();

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

configureUploadLimits({ maxFileSize: config.maxFileSize, memoryThreshold: config.memoryThreshold });

const queueMiddleware = createQueueMiddleware(config.maxConcurrentRequests);

app.use(timeoutMiddleware(config.requestTimeout));
app.use(loggingMiddleware);

app.get("/api/health", healthRoutes);
app.use("/api/image", sharedKeyAuth(config.sharedKey), queueMiddleware, createImageRoutes());
app.use("/api/font", sharedKeyAuth(config.sharedKey), queueMiddleware, createFontRoutes());
app.use(errorHandler);

app.listen(config.port, config.host, () => {
  logActiveTransports(logger);
  logger.info(`media-processor listening on ${config.host}:${config.port}`);
});

export { app };
