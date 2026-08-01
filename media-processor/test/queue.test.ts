import { describe, it, expect, vi, beforeEach } from "vitest";
import { createQueueMiddleware } from "../src/middleware/queue.js";
import type { Request, Response, NextFunction } from "express";
import { EventEmitter } from "node:events";

function mockRes() {
  const res = new EventEmitter() as any;
  res.status = vi.fn().mockReturnThis();
  res.json = vi.fn().mockReturnThis();
  res.send = vi.fn().mockReturnThis();
  res.headersSent = false;
  res.writableEnded = false;
  return res as Response;
}

function mockReq() {
  return {} as Request;
}

describe("queueMiddleware", () => {
  it("calls next() when queue has capacity", async () => {
    const middleware = createQueueMiddleware(1);
    const req = mockReq();
    const res = mockRes();
    const next = vi.fn();

    middleware(req, res, next);

    expect(next).toHaveBeenCalled();
  });

  it("skips next() when res.writableEnded is true (timeout already sent)", async () => {
    const middleware = createQueueMiddleware(1);
    const req = mockReq();
    const res = mockRes();
    (res as any).writableEnded = true;
    const next = vi.fn();

    middleware(req, res, next);

    // next() should NOT be called because response already ended
    expect(next).not.toHaveBeenCalled();
  });

  it("queues requests when concurrency limit reached", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    // First request takes the slot
    middleware(req1, res1, next1);
    expect(next1).toHaveBeenCalled();

    // Second request should queue
    middleware(req2, res2, next2);
    expect(next2).not.toHaveBeenCalled();

    // Finish first request
    res1.emit("finish");

    // Now second request should proceed
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(next2).toHaveBeenCalled();
  });

  it("resolves promise when response finishes", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // Finish first request
    res1.emit("finish");

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(next2).toHaveBeenCalled();
  });

  it("resolves promise when response closes", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // Close first request
    res1.emit("close");

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(next2).toHaveBeenCalled();
  });
});
