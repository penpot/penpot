import { timingSafeEqual } from "node:crypto";
import type { Request, Response, NextFunction } from "express";

export function sharedKeyAuth(expectedKey: string | null) {
  if (expectedKey === null) {
    return (_req: Request, res: Response, _next: NextFunction): void => {
      res.status(403).json({ type: "authorization", code: "forbidden", hint: "Shared key not configured" });
    };
  }

  return (req: Request, res: Response, next: NextFunction): void => {
    const provided = req.headers["x-shared-key"];
    if (typeof provided !== "string") {
      res.status(403).json({ type: "authorization", code: "forbidden" });
      return;
    }

    const providedBuf = Buffer.from(provided);
    const expectedBuf = Buffer.from(expectedKey);

    if (providedBuf.length === expectedBuf.length && timingSafeEqual(providedBuf, expectedBuf)) {
      next();
    } else {
      res.status(403).json({ type: "authorization", code: "forbidden" });
    }
  };
}
