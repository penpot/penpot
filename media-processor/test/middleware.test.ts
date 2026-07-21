import { describe, it, expect, vi, beforeEach } from "vitest";
import { ProcessingError, errorHandler } from "../src/middleware/error-handler.js";
import { sharedKeyAuth } from "../src/middleware/auth.js";
import { throwValidation, throwRestriction } from "../src/services/errors.js";
import multer from "multer";
import type { Request, Response, NextFunction } from "express";

function mockRes() {
  const res = {
    status: vi.fn().mockReturnThis(),
    json: vi.fn().mockReturnThis(),
    send: vi.fn().mockReturnThis(),
    headersSent: false,
  };
  return res as unknown as Response;
}

function mockReq() {
  return {} as Request;
}

describe("ProcessingError", () => {
  it("stores statusCode", () => {
    const err = new ProcessingError(400, {
      type: "validation",
      code: "test-error",
    });
    expect(err.statusCode).toBe(400);
  });

  it("stores errorBody", () => {
    const body = { type: "validation" as const, code: "test-error", hint: "details" };
    const err = new ProcessingError(400, body);
    expect(err.errorBody).toEqual(body);
  });

  it("message defaults to code when no hint", () => {
    const err = new ProcessingError(400, {
      type: "validation",
      code: "test-error",
    });
    expect(err.message).toBe("test-error");
  });

  it("message uses hint when provided", () => {
    const err = new ProcessingError(400, {
      type: "validation",
      code: "test-error",
      hint: "something went wrong",
    });
    expect(err.message).toBe("something went wrong");
  });

  it("is an instance of Error", () => {
    const err = new ProcessingError(500, {
      type: "internal",
      code: "internal-error",
    });
    expect(err).toBeInstanceOf(Error);
  });
});

describe("throwValidation", () => {
  it("throws ProcessingError with status 400", () => {
    try {
      throwValidation("bad-input", "invalid value");
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as ProcessingError;
      expect(pe.statusCode).toBe(400);
      expect(pe.errorBody.type).toBe("validation");
      expect(pe.errorBody.code).toBe("bad-input");
      expect(pe.errorBody.hint).toBe("invalid value");
    }
  });

  it("works without hint", () => {
    try {
      throwValidation("bad-input");
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as ProcessingError;
      expect(pe.errorBody.hint).toBeUndefined();
    }
  });
});

describe("throwRestriction", () => {
  it("throws ProcessingError with status 413", () => {
    try {
      throwRestriction("too-large", "file exceeds limit");
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as ProcessingError;
      expect(pe.statusCode).toBe(413);
      expect(pe.errorBody.type).toBe("restriction");
      expect(pe.errorBody.code).toBe("too-large");
      expect(pe.errorBody.hint).toBe("file exceeds limit");
    }
  });

  it("works without hint", () => {
    try {
      throwRestriction("too-large");
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as ProcessingError;
      expect(pe.errorBody.hint).toBeUndefined();
    }
  });
});

describe("errorHandler", () => {
  let res: ReturnType<typeof mockRes>;
  let next: NextFunction;

  beforeEach(() => {
    res = mockRes();
    next = vi.fn();
  });

  it("handles ProcessingError (400 validation)", () => {
    const err = new ProcessingError(400, {
      type: "validation",
      code: "bad-input",
      hint: "invalid value",
    });

    errorHandler(err, mockReq(), res, next);

    expect(res.status).toHaveBeenCalledWith(400);
    expect(res.json).toHaveBeenCalledWith({
      type: "validation",
      code: "bad-input",
      hint: "invalid value",
    });
  });

  it("handles ProcessingError (413 restriction)", () => {
    const err = new ProcessingError(413, {
      type: "restriction",
      code: "payload-too-large",
    });

    errorHandler(err, mockReq(), res, next);

    expect(res.status).toHaveBeenCalledWith(413);
    expect(res.json).toHaveBeenCalledWith({
      type: "restriction",
      code: "payload-too-large",
    });
  });

  it("handles MulterError LIMIT_FILE_SIZE as 413", () => {
    const err = new multer.MulterError("LIMIT_FILE_SIZE");
    errorHandler(err, mockReq(), res, next);

    expect(res.status).toHaveBeenCalledWith(413);
    expect(res.json).toHaveBeenCalledWith({
      type: "restriction",
      code: "payload-too-large",
    });
  });

  it("handles generic Error as 500", () => {
    const err = new Error("something broke");

    errorHandler(err, mockReq(), res, next);

    expect(res.status).toHaveBeenCalledWith(500);
    expect(res.json).toHaveBeenCalledWith({
      type: "internal",
      code: "processing-error",
      hint: "Internal server error",
    });
  });

  it("handles Error with empty message", () => {
    const err = new Error("");

    errorHandler(err, mockReq(), res, next);

    expect(res.status).toHaveBeenCalledWith(500);
    expect(res.json).toHaveBeenCalledWith({
      type: "internal",
      code: "processing-error",
      hint: "Internal server error",
    });
  });
});

describe("sharedKeyAuth", () => {
  let res: ReturnType<typeof mockRes>;
  let next: NextFunction;

  beforeEach(() => {
    res = mockRes();
    next = vi.fn();
  });

  it("returns 403 when expectedKey is null", () => {
    const middleware = sharedKeyAuth(null);
    const req = { headers: {} } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(res.json).toHaveBeenCalledWith({
      type: "internal",
      code: "forbidden",
      hint: "Shared key not configured",
    });
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 when expectedKey is null regardless of NODE_ENV", () => {
    const originalEnv = process.env.NODE_ENV;
    delete process.env.NODE_ENV;
    try {
      const middleware = sharedKeyAuth(null);
      const req = { headers: {} } as unknown as Request;
      middleware(req, res, next);
      expect(res.status).toHaveBeenCalledWith(403);
      expect(next).not.toHaveBeenCalled();
    } finally {
      process.env.NODE_ENV = originalEnv;
    }
  });

  it("passes through with correct key", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: { "x-shared-key": "test-key" } } as unknown as Request;
    middleware(req, res, next);
    expect(next).toHaveBeenCalled();
    expect(res.status).not.toHaveBeenCalled();
  });

  it("returns 403 with wrong key", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: { "x-shared-key": "wrong-key" } } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(res.json).toHaveBeenCalledWith({ type: "internal", code: "forbidden" });
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 with missing header", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: {} } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(res.json).toHaveBeenCalledWith({ type: "internal", code: "forbidden" });
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 with undefined header value", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: { "x-shared-key": undefined } } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 when key is null and NODE_ENV is production", () => {
    const originalEnv = process.env.NODE_ENV;
    process.env.NODE_ENV = "production";
    try {
      const middleware = sharedKeyAuth(null);
      const req = { headers: {} } as unknown as Request;
      middleware(req, res, next);
      expect(res.status).toHaveBeenCalledWith(403);
      expect(res.json).toHaveBeenCalledWith({
        type: "internal",
        code: "forbidden",
        hint: "Shared key not configured",
      });
      expect(next).not.toHaveBeenCalled();
    } finally {
      process.env.NODE_ENV = originalEnv;
    }
  });
});
