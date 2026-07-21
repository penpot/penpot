import { ProcessingError } from "../middleware/error-handler.js";
import type { AppError } from "../types.js";

export function throwValidation(code: string, hint?: string): never {
  throw new ProcessingError(400, {
    type: "validation",
    code,
    hint,
  } satisfies AppError);
}

export function throwRestriction(code: string, hint?: string): never {
  throw new ProcessingError(413, {
    type: "restriction",
    code,
    hint,
  } satisfies AppError);
}

export function throwProcessing(code: string, hint?: string): never {
  throw new ProcessingError(503, {
    type: "internal",
    code,
    hint,
  } satisfies AppError);
}
