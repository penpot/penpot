import express, { type Express } from "express";
import { healthRoutes } from "./routes/health.js";
import { createImageRoutes } from "./routes/image.js";
import { createFontRoutes } from "./routes/font.js";
import { createSvgRoutes } from "./routes/svg.js";
import { errorHandler } from "./middleware/error-handler.js";
import { timeoutMiddleware } from "./middleware/timeout.js";
import { sharedKeyAuth } from "./middleware/auth.js";
import { createQueueMiddleware } from "./middleware/queue.js";
import { loggingMiddleware } from "./middleware/logging.js";
import type { AppConfig } from "./types.js";

/**
 * Build the HTTP app. Kept apart from `index.ts` (which loads the config, wires
 * the services and listens) so tests can build the real app without starting a
 * server.
 */
export function createApp(config: Pick<AppConfig, "sharedKey" | "maxConcurrentRequests" | "requestTimeout">): Express {
  const app = express();

  // Image and font share one queue. SVG gets its own: an SVG request waiting for
  // a free worker holds its queue slot for its whole lifetime, and on a shared
  // queue that wait would block image and font requests behind it.
  const queueMiddleware = createQueueMiddleware(config.maxConcurrentRequests);
  const svgQueueMiddleware = createQueueMiddleware(config.maxConcurrentRequests);

  app.use(timeoutMiddleware(config.requestTimeout));
  app.use(loggingMiddleware);

  app.get("/api/health", healthRoutes);
  app.use("/api/image", sharedKeyAuth(config.sharedKey), queueMiddleware, createImageRoutes());
  app.use("/api/font", sharedKeyAuth(config.sharedKey), queueMiddleware, createFontRoutes());
  app.use("/api/svg", sharedKeyAuth(config.sharedKey), svgQueueMiddleware, createSvgRoutes());
  app.use(errorHandler);

  return app;
}
