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
            res.on("finish", resolve);
            res.on("close", resolve);
            next();
          })
      )
      .catch(() => next(new Error("Request processing failed")));
  };
}
