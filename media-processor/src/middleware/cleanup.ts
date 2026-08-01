import { rm } from "node:fs/promises";
import type { Request, Response, NextFunction } from "express";

export function cleanupMiddleware(req: Request, _res: Response, next: NextFunction): void {
  _res.on("finish", cleanup);
  _res.on("close", cleanup);

  async function cleanup() {
    const file = req.file as (Express.Multer.File & { path?: string }) | undefined;
    if (file?.path) {
      await rm(file.path, { force: true }).catch(() => {});
    }
  }

  next();
}
