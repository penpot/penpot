import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { createHybridStorage } from "../src/upload-storage.js";
import type { Request } from "express";
import { Readable } from "node:stream";
import { rm } from "node:fs/promises";

function mockReq(contentLength?: string): Request {
  const headers: Record<string, string> = {};
  if (contentLength !== undefined) {
    headers["content-length"] = contentLength;
  }
  return { headers } as Request;
}

function mockFile(content: string = "test content") {
  const stream = Readable.from([content]);
  return {
    fieldname: "file",
    originalname: "test.txt",
    encoding: "7bit",
    mimetype: "text/plain",
    stream,
  } as Express.Multer.File;
}

describe("createHybridStorage", () => {
  let storage: ReturnType<typeof createHybridStorage>;
  let tempDirs: string[] = [];

  beforeEach(() => {
    storage = createHybridStorage({ memoryThreshold: 1024 });
  });

  afterEach(async () => {
    for (const dir of tempDirs) {
      await rm(dir, { recursive: true, force: true });
    }
    tempDirs = [];
  });

  it("uses memory storage when Content-Length is below threshold", async () => {
    const req = mockReq("100");
    const file = mockFile("small content");

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeUndefined();
          expect((info as any).buffer).toBeDefined();
          resolve();
        }
      });
    });
  });

  it("uses disk storage when Content-Length is above threshold", async () => {
    const req = mockReq("2048");
    const file = mockFile("x".repeat(2048));

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeDefined();
          expect((info as any).destination).toBeDefined();
          tempDirs.push((info as any).destination);
          resolve();
        }
      });
    });
  });

  it("uses disk storage when Content-Length is absent (chunked transfer)", async () => {
    const req = mockReq();
    const file = mockFile("chunked content");

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeDefined();
          expect((info as any).destination).toBeDefined();
          tempDirs.push((info as any).destination);
          resolve();
        }
      });
    });
  });

  it("uses disk storage when Content-Length is invalid", async () => {
    const req = mockReq("not-a-number");
    const file = mockFile("content");

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeDefined();
          tempDirs.push((info as any).destination);
          resolve();
        }
      });
    });
  });

  it("removes file from disk", async () => {
    const req = mockReq("2048");
    const file = mockFile("x".repeat(2048));

    const info = await new Promise<any>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else resolve(info);
      });
    });

    tempDirs.push(info.destination);

    await new Promise<void>((resolve, reject) => {
      storage._removeFile(req, { ...file, path: info.path } as any, (err) => {
        if (err) reject(err);
        else resolve();
      });
    });
  });

  it("uses memory storage when Content-Length is 0", async () => {
    const req = mockReq("0");
    const file = mockFile("");

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeUndefined();
          expect((info as any).buffer).toBeDefined();
          resolve();
        }
      });
    });
  });

  it("uses disk storage when Content-Length equals threshold", async () => {
    const req = mockReq("1024");
    const file = mockFile("x".repeat(1024));

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeDefined();
          expect((info as any).destination).toBeDefined();
          tempDirs.push((info as any).destination);
          resolve();
        }
      });
    });
  });

  it("uses disk storage when Content-Length is very large", async () => {
    const req = mockReq("1073741824"); // 1GB
    const file = mockFile("x"); // Small actual content, but large Content-Length

    await new Promise<void>((resolve, reject) => {
      storage._handleFile(req, file, (err, info) => {
        if (err) reject(err);
        else {
          expect(info).toBeDefined();
          expect((info as any).path).toBeDefined();
          expect((info as any).destination).toBeDefined();
          tempDirs.push((info as any).destination);
          resolve();
        }
      });
    });
  });

  it("reuses the same temp directory for concurrent uploads", async () => {
    const req1 = mockReq("2048");
    const file1 = mockFile("x".repeat(2048));
    const req2 = mockReq("2048");
    const file2 = mockFile("y".repeat(2048));

    const [info1, info2] = await Promise.all([
      new Promise<any>((resolve, reject) => {
        storage._handleFile(req1, file1, (err, info) => {
          if (err) reject(err);
          else resolve(info);
        });
      }),
      new Promise<any>((resolve, reject) => {
        storage._handleFile(req2, file2, (err, info) => {
          if (err) reject(err);
          else resolve(info);
        });
      }),
    ]);

    tempDirs.push(info1.destination);
    tempDirs.push(info2.destination);

    // Both uploads should use the same temp directory
    expect(info1.destination).toBe(info2.destination);
  });
});
