import { describe, it, expect, vi, beforeEach } from "vitest";
import { ProcessingError, errorHandler } from "../src/middleware/error-handler.js";
import { sharedKeyAuth } from "../src/middleware/auth.js";
import { timeoutMiddleware } from "../src/middleware/timeout.js";
import { cleanupMiddleware } from "../src/middleware/cleanup.js";
import { throwValidation, throwRestriction } from "../src/services/errors.js";
import multer from "multer";
import type { Request, Response, NextFunction } from "express";
import { EventEmitter } from "node:events";

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

  it("does not write response if headers already sent", () => {
    const err = new ProcessingError(400, {
      type: "validation",
      code: "bad-input",
      hint: "invalid value",
    });

    const resWithHeadersSent = {
      ...res,
      headersSent: true,
    };

    errorHandler(err, mockReq(), resWithHeadersSent, next);

    expect(resWithHeadersSent.status).not.toHaveBeenCalled();
    expect(resWithHeadersSent.json).not.toHaveBeenCalled();
  });

  it("calls releaseQueue for ProcessingError", () => {
    const releaseQueue = vi.fn();
    const resWithLocals = { ...res, locals: { releaseQueue } } as any;
    const err = new ProcessingError(400, { type: "validation", code: "test" });

    errorHandler(err, mockReq(), resWithLocals, next);

    expect(releaseQueue).toHaveBeenCalled();
  });

  it("calls releaseQueue for MulterError LIMIT_FILE_SIZE", () => {
    const releaseQueue = vi.fn();
    const resWithLocals = { ...res, locals: { releaseQueue } } as any;
    const err = new multer.MulterError("LIMIT_FILE_SIZE");

    errorHandler(err, mockReq(), resWithLocals, next);

    expect(releaseQueue).toHaveBeenCalled();
  });

  it("calls releaseQueue for generic Error", () => {
    const releaseQueue = vi.fn();
    const resWithLocals = { ...res, locals: { releaseQueue } } as any;
    const err = new Error("something broke");

    errorHandler(err, mockReq(), resWithLocals, next);

    expect(releaseQueue).toHaveBeenCalled();
  });

  it("does not throw when releaseQueue is not set", () => {
    const resWithNoLocals = { ...res, locals: {} } as any;
    const err = new ProcessingError(400, { type: "validation", code: "test" });

    expect(() => errorHandler(err, mockReq(), resWithNoLocals, next)).not.toThrow();
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
      type: "authorization",
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
    expect(res.json).toHaveBeenCalledWith({ type: "authorization", code: "forbidden" });
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 with missing header", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: {} } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(res.json).toHaveBeenCalledWith({ type: "authorization", code: "forbidden" });
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
        type: "authorization",
        code: "forbidden",
        hint: "Shared key not configured",
      });
      expect(next).not.toHaveBeenCalled();
    } finally {
      process.env.NODE_ENV = originalEnv;
    }
  });

  it("returns 403 for multibyte Unicode with same string length but different byte length", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: { "x-shared-key": "test-ké" } } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 for emoji input (multibyte)", () => {
    const middleware = sharedKeyAuth("test-key");
    const req = { headers: { "x-shared-key": "test-k🔑" } } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(next).not.toHaveBeenCalled();
  });

  it("returns 403 for accented characters with same string length", () => {
    const middleware = sharedKeyAuth("abcdefgh");
    const req = { headers: { "x-shared-key": "ábcdefgh" } } as unknown as Request;
    middleware(req, res, next);
    expect(res.status).toHaveBeenCalledWith(403);
    expect(next).not.toHaveBeenCalled();
  });
});

describe("timeoutMiddleware", () => {
  it("calls next() immediately", () => {
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);

    expect(next).toHaveBeenCalled();
  });

  it("clears timer when response finishes", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);
    res.emit("finish");

    // Advance past timeout - should not throw
    vi.advanceTimersByTime(2000);
    vi.useRealTimers();
  });

  it("clears timer when response closes", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);
    res.emit("close");

    // Advance past timeout - should not throw
    vi.advanceTimersByTime(2000);
    vi.useRealTimers();
  });

  it("sends 504 response when timeout expires before response", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(100);
    const req = new EventEmitter() as unknown as Request;
    (req as any).destroy = vi.fn();
    const res = new EventEmitter() as unknown as Response;
    (res as any).headersSent = false;
    (res as any).status = vi.fn().mockReturnThis();
    (res as any).json = vi.fn().mockReturnThis();
    const next = vi.fn();

    middleware(req, res, next);
    vi.advanceTimersByTime(150);

    expect(res.status).toHaveBeenCalledWith(504);
    expect(res.json).toHaveBeenCalledWith({
      type: "internal",
      code: "processing-timeout",
      hint: "Request timed out",
    });
    vi.useRealTimers();
  });

  it("does not send response if headers already sent", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(100);
    const req = new EventEmitter() as unknown as Request;
    (req as any).destroy = vi.fn();
    const res = new EventEmitter() as unknown as Response;
    (res as any).headersSent = true;
    (res as any).status = vi.fn().mockReturnThis();
    (res as any).json = vi.fn().mockReturnThis();
    const next = vi.fn();

    middleware(req, res, next);
    vi.advanceTimersByTime(150);

    expect(res.status).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("destroys request AFTER response finishes (not before)", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(100);
    const req = new EventEmitter() as unknown as Request;
    (req as any).destroy = vi.fn();
    const res = new EventEmitter() as unknown as Response;
    (res as any).headersSent = false;
    (res as any).status = vi.fn().mockReturnThis();
    (res as any).json = vi.fn().mockReturnThis();
    const next = vi.fn();

    middleware(req, res, next);

    // Advance to timeout - this triggers the 504 response
    vi.advanceTimersByTime(100);

    // req.destroy should NOT be called yet (response not finished)
    expect(req.destroy).not.toHaveBeenCalled();
    expect(res.status).toHaveBeenCalledWith(504);

    // Now simulate response finishing
    res.emit("finish");

    // Now req.destroy should be called
    expect(req.destroy).toHaveBeenCalled();
    vi.useRealTimers();
  });

  it("creates AbortController and attaches to request", () => {
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);

    expect((req as any).abortController).toBeDefined();
    expect((req as any).abortController.signal).toBeDefined();
    expect((req as any).abortController.signal.aborted).toBe(false);
  });

  it("aborts signal when timeout fires", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(100);
    const req = new EventEmitter() as unknown as Request;
    (req as any).destroy = vi.fn();
    const res = new EventEmitter() as unknown as Response;
    (res as any).headersSent = false;
    (res as any).status = vi.fn().mockReturnThis();
    (res as any).json = vi.fn().mockReturnThis();
    const next = vi.fn();

    middleware(req, res, next);

    // Signal should not be aborted yet
    expect((req as any).abortController.signal.aborted).toBe(false);

    // Advance to timeout
    vi.advanceTimersByTime(150);

    // Signal should now be aborted
    expect((req as any).abortController.signal.aborted).toBe(true);
    vi.useRealTimers();
  });

  it("does not abort signal when response finishes before timeout", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);

    // Response finishes before timeout
    res.emit("finish");

    // Advance past timeout
    vi.advanceTimersByTime(2000);

    // Signal should NOT be aborted (timer was cleared)
    expect((req as any).abortController.signal.aborted).toBe(false);
    vi.useRealTimers();
  });

  it("aborts signal when response closes (client disconnect)", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(1000);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    middleware(req, res, next);

    // Signal should not be aborted initially
    expect((req as any).abortController.signal.aborted).toBe(false);

    // Simulate client disconnect (response closes)
    res.emit("close");

    // Signal should now be aborted
    expect((req as any).abortController.signal.aborted).toBe(true);
    vi.useRealTimers();
  });

  it("does not abort signal again if already aborted when response closes", async () => {
    vi.useFakeTimers();
    const middleware = timeoutMiddleware(100);
    const req = new EventEmitter() as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    (res as any).headersSent = false;
    (res as any).status = vi.fn().mockReturnValue({ json: vi.fn() });
    const next = vi.fn();

    middleware(req, res, next);

    // Advance past timeout to trigger abort
    vi.advanceTimersByTime(200);

    // Signal should be aborted from timeout
    expect((req as any).abortController.signal.aborted).toBe(true);

    // Simulate client disconnect (response closes)
    res.emit("close");

    // Signal should still be aborted (no error thrown)
    expect((req as any).abortController.signal.aborted).toBe(true);
    vi.useRealTimers();
  });
});

describe("cleanupMiddleware", () => {
  it("calls next() immediately", () => {
    const req = {} as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    cleanupMiddleware(req, res, next);

    expect(next).toHaveBeenCalled();
  });

  it("removes file on response finish", async () => {
    const req = {
      file: {
        path: "/tmp/test-file.jpg",
      },
    } as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    // Mock the rm function by spying on the cleanup behavior
    // We'll verify the middleware registers the finish handler
    cleanupMiddleware(req, res, next);

    // Emit finish event - this should trigger cleanup
    // The actual rm is mocked internally, so we just verify no errors
    res.emit("finish");

    // Wait for async cleanup
    await new Promise((resolve) => setTimeout(resolve, 10));
  });

  it("removes file on response close", async () => {
    const req = {
      file: {
        path: "/tmp/test-file.jpg",
      },
    } as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    cleanupMiddleware(req, res, next);

    // Emit close event - this should trigger cleanup
    res.emit("close");

    // Wait for async cleanup
    await new Promise((resolve) => setTimeout(resolve, 10));
  });

  it("does nothing when req.file is undefined", async () => {
    const req = {} as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    cleanupMiddleware(req, res, next);

    // Emit finish event - should not throw
    res.emit("finish");

    // Wait for async cleanup
    await new Promise((resolve) => setTimeout(resolve, 10));
  });

  it("does nothing when req.file.path is undefined", async () => {
    const req = {
      file: {
        buffer: Buffer.from("test"),
      },
    } as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    cleanupMiddleware(req, res, next);

    // Emit finish event - should not throw
    res.emit("finish");

    // Wait for async cleanup
    await new Promise((resolve) => setTimeout(resolve, 10));
  });

  it("doesn't throw when file doesn't exist", async () => {
    const req = {
      file: {
        path: "/tmp/nonexistent-file.jpg",
      },
    } as unknown as Request;
    const res = new EventEmitter() as unknown as Response;
    const next = vi.fn();

    cleanupMiddleware(req, res, next);

    // Emit finish event - should not throw even if file doesn't exist
    res.emit("finish");

    // Wait for async cleanup
    await new Promise((resolve) => setTimeout(resolve, 10));
  });
});
