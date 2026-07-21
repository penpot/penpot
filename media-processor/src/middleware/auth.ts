import { timingSafeEqual } from "node:crypto";
import type { Request, Response, NextFunction } from "express";

export function sharedKeyAuth(expectedKey: string | null) {
  if (expectedKey === null) {
    return (_req: Request, res: Response, _next: NextFunction): void => {
      res.status(403).json({ type: "internal", code: "forbidden", hint: "Shared key not configured" });
    };
  }

  return (req: Request, res: Response, next: NextFunction): void => {
    const provided = req.headers["x-shared-key"];
    if (
      typeof provided === "string" &&
      provided.length === expectedKey.length &&
      timingSafeEqual(Buffer.from(provided), Buffer.from(expectedKey))
    ) {
      next();
    } else {
      res.status(403).json({ type: "internal", code: "forbidden" });
    }
  };
}
