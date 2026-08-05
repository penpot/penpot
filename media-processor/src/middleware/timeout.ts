import type { Request, Response, NextFunction } from "express";

export function timeoutMiddleware(timeout: number) {
  return (req: Request, res: Response, next: NextFunction): void => {
    // Create AbortController for request cancellation
    const abortController = new AbortController();
    (req as any).abortController = abortController;

    const timer = setTimeout(() => {
      if (!res.headersSent) {
        res.status(504).json({
          type: "internal",
          code: "processing-timeout",
          hint: "Request timed out",
        });
        // Abort the signal to cancel ongoing processing
        abortController.abort();
        res.on("finish", () => req.destroy());
      }
    }, timeout);

    // Clear timer on finish (successful completion)
    res.on("finish", () => clearTimeout(timer));

    // Clear timer and abort signal on close (client disconnect)
    res.on("close", () => {
      clearTimeout(timer);
      if (!abortController.signal.aborted) {
        abortController.abort();
      }
    });

    next();
  };
}
