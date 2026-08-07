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

            // Store releaseQueue callback on res.locals so route handlers and error handler can call it
            (res as any).locals = (res as any).locals || {};
            (res as any).locals.releaseQueue = release;

            next();
          })
      )
      .catch((err) => next(err instanceof Error ? err : new Error("Request processing failed")));
  };
}
