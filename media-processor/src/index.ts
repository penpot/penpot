import express, { type Express, type Request, type Response, type NextFunction } from "express";
import { loadConfig } from "./config.js";
import { initLogger, logger, logActiveTransports } from "./logger.js";
import { healthRoutes } from "./routes/health.js";
import { createImageRoutes } from "./routes/image.js";
import { createFontRoutes } from "./routes/font.js";
import { errorHandler } from "./middleware/error-handler.js";
import { timeoutMiddleware } from "./middleware/timeout.js";
import { sharedKeyAuth } from "./middleware/auth.js";
import { configureImageLimits } from "./services/image.js";
import { configureFontLimits } from "./services/font.js";
import { configureUploadLimits } from "./upload.js";
import sharp from "sharp";
import PQueue from "p-queue";

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

const queue = new PQueue({ concurrency: config.maxConcurrentRequests });

function queueMiddleware(_req: Request, res: Response, next: NextFunction): void {
  queue
    .add(
      () =>
        new Promise<void>((resolve) => {
          res.on("finish", resolve);
          res.on("close", resolve);
          next();
        })
    )
    .catch(() => next(new Error("Request processing failed")));
}

app.use(timeoutMiddleware(config.requestTimeout));

const OP_NAMES: Record<string, string> = {
  "POST /api/image/info": "image/info",
  "POST /api/image/thumbnail": "image/thumbnail",
  "POST /api/font/convert": "font/convert",
};

app.use((req: Request, res: Response, next: NextFunction) => {
  const start = Date.now();
  res.on("finish", () => {
    const path = req.originalUrl?.split("?")[0];
    const op = OP_NAMES[`${req.method} ${path}`];
    if (op) {
      const meta = res.locals.opMeta ? `, ${res.locals.opMeta}` : "";
      logger.info(`op=${op}${meta}, status=${res.statusCode}, elapsed=${Date.now() - start}ms`);
    }
  });
  next();
});

app.get("/api/health", healthRoutes);
app.use("/api/image", sharedKeyAuth(config.sharedKey), queueMiddleware, createImageRoutes());
app.use("/api/font", sharedKeyAuth(config.sharedKey), queueMiddleware, createFontRoutes());
app.use(errorHandler);

app.listen(config.port, config.host, () => {
  logActiveTransports(logger);
  logger.info(`media-processor listening on ${config.host}:${config.port}`);
});

export { app };
