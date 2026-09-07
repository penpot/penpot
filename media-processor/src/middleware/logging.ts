import type { Request, Response, NextFunction } from "express";
import { logger } from "../logger.js";

const OP_NAMES: Record<string, string> = {
  "POST /api/image/info": "image/info",
  "POST /api/image/thumbnail": "image/thumbnail",
  "POST /api/font/convert": "font/convert",
};

export function loggingMiddleware(req: Request, res: Response, next: NextFunction): void {
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
}
