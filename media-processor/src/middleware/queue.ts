import type { Request, Response, NextFunction } from "express";
import PQueue from "p-queue";

export function createQueueMiddleware(concurrency: number) {
  const queue = new PQueue({ concurrency });

  return function queueMiddleware(_req: Request, res: Response, next: NextFunction): void {
    queue
      .add(
        () =>
          new Promise<void>((resolve) => {
            if (res.writableEnded) {
              resolve();
              return;
            }

            let released = false;
            const release = () => {
              if (!released) {
                released = true;
                resolve();
              }
            };

            // Store releaseQueue callback on res.locals so route handlers can call it
            // when processing completes (in finally block)
            (res as any).locals = (res as any).locals || {};
            (res as any).locals.releaseQueue = release;

            // Fallback: release when response finishes (covers error handler path)
            // This ensures queue slot is released even if Multer throws before route handler runs
            res.on("finish", release);
            res.on("close", release);

            next();
          })
      )
      .catch(() => next(new Error("Request processing failed")));
  };
}
