import { rm } from "node:fs/promises";
import type { Request, Response, NextFunction } from "express";
import { createLogger } from "../logger.js";

const logger = createLogger("cleanup");

export function cleanupMiddleware(req: Request, _res: Response, next: NextFunction): void {
  let cleaned = false;

  _res.on("finish", cleanup);
  _res.on("close", cleanup);

  async function cleanup() {
    if (cleaned) return;
    cleaned = true;
    const file = req.file as (Express.Multer.File & { path?: string }) | undefined;
    if (file?.path) {
      await rm(file.path, { force: true }).catch((err) => {
        logger.debug({ err, path: file.path }, "Failed to cleanup uploaded file");
      });
    }
  }

  next();
}
