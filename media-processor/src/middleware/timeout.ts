import type { Request, Response, NextFunction } from "express";

export function timeoutMiddleware(timeout: number) {
  return (req: Request, res: Response, next: NextFunction): void => {
    const timer = setTimeout(() => {
      if (!res.headersSent) {
        res.status(504).json({
          type: "internal",
          code: "processing-timeout",
          hint: "Request timed out",
        });
      }
      req.destroy();
    }, timeout);

    res.on("finish", () => clearTimeout(timer));
    res.on("close", () => clearTimeout(timer));
    next();
  };
}
