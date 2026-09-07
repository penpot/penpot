import type { Request, Response, NextFunction } from "express";
import type { AppError } from "../types.js";
import { createLogger } from "../logger.js";
import multer from "multer";

const logger = createLogger("error-handler");

export class ProcessingError extends Error {
  public readonly statusCode: number;
  public readonly errorBody: AppError;

  constructor(statusCode: number, body: AppError) {
    super(body.hint ?? body.code);
    this.statusCode = statusCode;
    this.errorBody = body;
  }
}

function releaseSlot(res: Response): void {
  const releaseQueue = (res as any).locals?.releaseQueue;
  if (releaseQueue) releaseQueue();
}

export function errorHandler(err: Error, _req: Request, res: Response, _next: NextFunction): void {
  if (res.headersSent) {
    return;
  }

  if (err instanceof ProcessingError) {
    logger.warn({ err, statusCode: err.statusCode }, "Processing error");
    res.status(err.statusCode).json(err.errorBody);
    releaseSlot(res);
    return;
  }

  if (err instanceof multer.MulterError) {
    if (err.code === "LIMIT_FILE_SIZE") {
      logger.warn({ err }, "Upload size limit exceeded");
      res.status(413).json({
        type: "restriction",
        code: "payload-too-large",
      });
      releaseSlot(res);
      return;
    }
  }

  logger.error({ err }, "Unhandled error");
  res.status(500).json({
    type: "internal",
    code: "processing-error",
    hint: "Internal server error",
  });
  releaseSlot(res);
}
