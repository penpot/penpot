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

            // Store releaseQueue callback on res.locals so route handlers can call it
            // when processing completes (in finally block)
            (res as any).locals = (res as any).locals || {};
            (res as any).locals.releaseQueue = resolve;
            next();
          })
      )
      .catch(() => next(new Error("Request processing failed")));
  };
}
