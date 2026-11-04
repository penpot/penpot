import type { AppError } from "./types.js";

/**
 * The error the worker knows how to turn into a reply. `errorBody` is the
 * wire shape (`type`, `code`, `hint`); `statusCode` is the classification the
 * tests assert (400 for invalid params, 413 for too large, 502 for a backend
 * failure) and nothing in `src` reads it.
 */
export class ProcessingError extends Error {
  public readonly statusCode: number;
  public readonly errorBody: AppError;

  constructor(statusCode: number, body: AppError) {
    super(body.hint ?? body.code);
    this.statusCode = statusCode;
    this.errorBody = body;
  }
}
