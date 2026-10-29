import type { AppError } from "./types.js";

/**
 * The error the worker knows how to turn into a reply. `errorBody` is the
 * wire shape (`type`, `code`, `hint`); `statusCode` is carried for the SVG
 * pool and the tests that inspect the error.
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
