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

    // Release first request's queue slot (simulating processing completion)
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    // Now second request should proceed
    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(next2).toHaveBeenCalled();
  });

  it("resolves promise when releaseQueue is called", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // Release first request's queue slot
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));
    expect(next2).toHaveBeenCalled();
  });

  it("processes requests sequentially with concurrency 1", async () => {
    const middleware = createQueueMiddleware(1);
    const order: number[] = [];

    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn(() => order.push(1));

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn(() => order.push(2));

    const req3 = mockReq();
    const res3 = mockRes();
    const next3 = vi.fn(() => order.push(3));

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);
    middleware(req3, res3, next3);

    // Only first should be called immediately
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();
    expect(next3).not.toHaveBeenCalled();

    // Release first request's queue slot
    const releaseQueue1 = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue1).toBeDefined();
    releaseQueue1();
    await new Promise((resolve) => setTimeout(resolve, 10));

    // Now second should be called
    expect(next2).toHaveBeenCalled();
    expect(next3).not.toHaveBeenCalled();

    // Release second request's queue slot
    const releaseQueue2 = (res2 as any).locals?.releaseQueue;
    expect(releaseQueue2).toBeDefined();
    releaseQueue2();
    await new Promise((resolve) => setTimeout(resolve, 10));

    // Now third should be called
    expect(next3).toHaveBeenCalled();

    // Verify sequential order
    expect(order).toEqual([1, 2, 3]);
  });

  it("processes requests in parallel with concurrency 10", async () => {
    const middleware = createQueueMiddleware(10);
    const calls: number[] = [];

    // Create 5 requests (less than concurrency limit)
    const requests = Array.from({ length: 5 }, (_, i) => {
      const req = mockReq();
      const res = mockRes();
      const next = vi.fn(() => calls.push(i));
      return { req, res, next };
    });

    // All should be called immediately
    requests.forEach(({ req, res, next }) => {
      middleware(req, res, next);
    });

    // All 5 should be called immediately since concurrency is 10
    expect(calls.length).toBe(5);
    expect(calls).toEqual([0, 1, 2, 3, 4]);
  });

  it("handles request errors gracefully", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // Simulate error by releasing queue slot (as would happen in finally block)
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));

    // Second request should still proceed even after first "error"
    expect(next2).toHaveBeenCalled();
  });

  it("doesn't block on slow requests within concurrency limit", async () => {
    const middleware = createQueueMiddleware(2);
    const calls: number[] = [];

    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn(() => calls.push(1));

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn(() => calls.push(2));

    const req3 = mockReq();
    const res3 = mockRes();
    const next3 = vi.fn(() => calls.push(3));

    // Start first two requests (concurrency is 2)
    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // Both should be called immediately
    expect(next1).toHaveBeenCalled();
    expect(next2).toHaveBeenCalled();
    expect(next3).not.toHaveBeenCalled();

    // Third request should wait
    middleware(req3, res3, next3);
    expect(next3).not.toHaveBeenCalled();

    // Release first request's queue slot
    const releaseQueue1 = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue1).toBeDefined();
    releaseQueue1();
    await new Promise((resolve) => setTimeout(resolve, 10));

    // Now third should proceed
    expect(next3).toHaveBeenCalled();
  });

  it("releases slot when error handler calls releaseQueue (covers Multer error path)", async () => {
    // This test verifies that the error handler releases the queue slot
    // by calling releaseQueue from res.locals. This covers the Multer error
    // case where the route handler never runs.
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // First request is processing, second is queued
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();

    // Simulate error handler calling releaseQueue (e.g., Multer error)
    const releaseQueue = (res1 as any).locals.releaseQueue;
    releaseQueue();
    await new Promise((resolve) => setTimeout(resolve, 10));

    // Second request should proceed because slot was released
    expect(next2).toHaveBeenCalled();
  });

  it("releases slot when releaseQueue callback is called", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // First request is processing, second is queued
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();

    // Simulate processing completing by calling releaseQueue
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));

    // Now second request should proceed
    expect(next2).toHaveBeenCalled();
  });

  it("releases slot on processing error (via finally block)", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // First request is processing, second is queued
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();

    // Simulate processing error and release in finally block
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));

    // Second request should proceed even after error
    expect(next2).toHaveBeenCalled();
  });

  it("releaseQueue is idempotent (can be called multiple times)", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // First request is processing, second is queued
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();

    // Call releaseQueue multiple times
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();
    releaseQueue(); // Should not throw or cause issues
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));

    // Second request should proceed
    expect(next2).toHaveBeenCalled();
  });

  it("holds queue slot when client disconnects (close event)", async () => {
    const middleware = createQueueMiddleware(1);
    const req1 = mockReq();
    const res1 = mockRes();
    const next1 = vi.fn();

    const req2 = mockReq();
    const res2 = mockRes();
    const next2 = vi.fn();

    middleware(req1, res1, next1);
    middleware(req2, res2, next2);

    // First request is processing, second is queued
    expect(next1).toHaveBeenCalled();
    expect(next2).not.toHaveBeenCalled();

    // Simulate client disconnect (close event)
    res1.emit("close");
    await new Promise((resolve) => setTimeout(resolve, 10));

    // Second request should NOT proceed because slot is still held
    expect(next2).not.toHaveBeenCalled();

    // Now release the slot (simulating processing completion)
    const releaseQueue = (res1 as any).locals?.releaseQueue;
    expect(releaseQueue).toBeDefined();
    releaseQueue();

    await new Promise((resolve) => setTimeout(resolve, 10));

    // Now second request should proceed
    expect(next2).toHaveBeenCalled();
  });
});
