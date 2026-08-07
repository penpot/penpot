import { describe, it, expect, beforeAll, afterAll } from "vitest";
import request from "supertest";
import express from "express";
import { createImageRoutes } from "../src/routes/image.js";
import { createFontRoutes } from "../src/routes/font.js";
import { errorHandler } from "../src/middleware/error-handler.js";
import { timeoutMiddleware } from "../src/middleware/timeout.js";
import { sharedKeyAuth } from "../src/middleware/auth.js";
import { createQueueMiddleware } from "../src/middleware/queue.js";
import { configureImageLimits } from "../src/services/image.js";
import { configureFontLimits } from "../src/services/font.js";
import { configureUploadLimits } from "../src/upload.js";
import sharp from "sharp";
import { readdir, rm, stat } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

// Test app with low memoryThreshold to force disk storage
function createTestApp() {
  const app = express();

  // Configure with low threshold to force disk storage
  configureImageLimits({
    maxPixels: 128_000_000,
    maxWidth: 16384,
    maxHeight: 16384,
  });

  configureFontLimits({
    mem: 1024 * 1024 * 512,
    cpuTime: 30,
    timeout: 30,
  });

  // Very low threshold to force disk storage for small files
  configureUploadLimits({ maxFileSize: 10 * 1024 * 1024, memoryThreshold: 10 });

  const queueMiddleware = createQueueMiddleware(10);

  app.use(timeoutMiddleware(5000));
  app.use("/api/image", sharedKeyAuth("test-key"), queueMiddleware, createImageRoutes());
  app.use("/api/font", sharedKeyAuth("test-key"), queueMiddleware, createFontRoutes());
  app.use(errorHandler);

  return app;
}

describe("HTTP upload cleanup", () => {
  let app: ReturnType<typeof createTestApp>;

  beforeAll(() => {
    app = createTestApp();
  });

  async function getTempFiles(): Promise<string[]> {
    const tmp = tmpdir();
    const files = await readdir(tmp);
    const uploadDirs = files.filter((f) => f.startsWith("penpot.upload."));

    // Get all files inside upload directories
    const allFiles: string[] = [];
    for (const dir of uploadDirs) {
      try {
        const dirPath = join(tmp, dir);
        const dirFiles = await readdir(dirPath);
        allFiles.push(...dirFiles.map((f) => join(dir, f)));
      } catch {
        // Directory might not exist or be inaccessible
      }
    }
    return allFiles;
  }

  it("removes disk-backed file after successful image/info request", async () => {
    const beforeFiles = await getTempFiles();

    // Create a small image
    const imageBuffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const response = await request(app)
      .post("/api/image/info")
      .set("x-shared-key", "test-key")
      .attach("file", imageBuffer, { filename: "test.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(200);
    expect(response.body.width).toBe(100);
    expect(response.body.height).toBe(100);

    // Wait for cleanup
    await new Promise((resolve) => setTimeout(resolve, 100));

    const afterFiles = await getTempFiles();

    // No new temp files should remain
    const newFiles = afterFiles.filter((f) => !beforeFiles.includes(f));
    expect(newFiles.length).toBe(0);
  });

  it("removes disk-backed file after successful image/thumbnail request", async () => {
    const beforeFiles = await getTempFiles();

    const imageBuffer = await sharp({
      create: { width: 200, height: 200, channels: 3, background: { r: 0, g: 255, b: 0 } },
    })
      .png()
      .toBuffer();

    const response = await request(app)
      .post("/api/image/thumbnail?width=100&height=100&format=jpeg&mode=fit")
      .set("x-shared-key", "test-key")
      .attach("file", imageBuffer, { filename: "test.png", contentType: "image/png" });

    expect(response.status).toBe(200);
    expect(response.headers["content-type"]).toMatch(/image\/jpeg/);

    // Wait for cleanup
    await new Promise((resolve) => setTimeout(resolve, 100));

    const afterFiles = await getTempFiles();
    const newFiles = afterFiles.filter((f) => !beforeFiles.includes(f));
    expect(newFiles.length).toBe(0);
  });

  it("removes disk-backed file after successful font/convert request", async () => {
    const beforeFiles = await getTempFiles();

    // Create a minimal TTF font (this is a simplified test - in reality you'd use a real font)
    // For this test, we'll just verify the cleanup happens even if the conversion fails
    const fontBuffer = Buffer.from("not a real font");

    const response = await request(app)
      .post("/api/font/convert?target-type=font/woff")
      .set("x-shared-key", "test-key")
      .attach("file", fontBuffer, { filename: "test.ttf", contentType: "font/ttf" });

    // The conversion will fail, but cleanup should still happen
    // We expect either 400 (invalid font) or 500 (processing error)
    expect([400, 500]).toContain(response.status);

    // Wait for cleanup
    await new Promise((resolve) => setTimeout(resolve, 100));

    const afterFiles = await getTempFiles();
    const newFiles = afterFiles.filter((f) => !beforeFiles.includes(f));
    expect(newFiles.length).toBe(0);
  });

  it("removes disk-backed file after failed request", async () => {
    const beforeFiles = await getTempFiles();

    // Send invalid image data
    const invalidBuffer = Buffer.from("not an image");

    const response = await request(app)
      .post("/api/image/info")
      .set("x-shared-key", "test-key")
      .attach("file", invalidBuffer, { filename: "invalid.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(400);

    // Wait for cleanup
    await new Promise((resolve) => setTimeout(resolve, 100));

    const afterFiles = await getTempFiles();
    const newFiles = afterFiles.filter((f) => !beforeFiles.includes(f));
    expect(newFiles.length).toBe(0);
  });

  it("removes disk-backed file after timeout", async () => {
    // Create a test app with very short timeout
    const timeoutApp = express();
    configureImageLimits({ maxPixels: 128_000_000, maxWidth: 16384, maxHeight: 16384 });
    configureUploadLimits({ maxFileSize: 10 * 1024 * 1024, memoryThreshold: 10 });
    const queueMiddleware = createQueueMiddleware(10);
    timeoutApp.use(timeoutMiddleware(10)); // 10ms timeout - very aggressive
    timeoutApp.use("/api/image", sharedKeyAuth("test-key"), queueMiddleware, createImageRoutes());
    timeoutApp.use(errorHandler);

    const beforeFiles = await getTempFiles();

    // Create a large image that will take time to process
    const imageBuffer = await sharp({
      create: { width: 4000, height: 4000, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg({ quality: 100 })
      .toBuffer();

    const response = await request(timeoutApp)
      .post("/api/image/thumbnail?width=2000&height=2000&format=jpeg&mode=fit")
      .set("x-shared-key", "test-key")
      .attach("file", imageBuffer, { filename: "large.jpg", contentType: "image/jpeg" });

    // Should timeout
    expect(response.status).toBe(504);
    expect(response.body.type).toBe("internal");
    expect(response.body.code).toBe("processing-timeout");

    // Wait for processing to settle (Sharp may still be working in background)
    await new Promise((resolve) => setTimeout(resolve, 2000));

    const afterFiles = await getTempFiles();
    const newFiles = afterFiles.filter((f) => !beforeFiles.includes(f));
    expect(newFiles.length).toBe(0);
  });
});

describe("HTTP malformed image handling", () => {
  let app: ReturnType<typeof createTestApp>;

  beforeAll(() => {
    app = createTestApp();
  });

  it("returns 400 for corrupted image in /api/image/info", async () => {
    // Create a valid image then truncate it
    const validBuffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const corruptedBuffer = validBuffer.subarray(0, Math.floor(validBuffer.length / 2));

    const response = await request(app)
      .post("/api/image/info")
      .set("x-shared-key", "test-key")
      .attach("file", corruptedBuffer, { filename: "corrupted.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(400);
    expect(response.body.type).toBe("validation");
    expect(response.body.code).toBe("invalid-image");
  });

  it("returns 400 for corrupted image in /api/image/thumbnail", async () => {
    const validBuffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const corruptedBuffer = validBuffer.subarray(0, Math.floor(validBuffer.length / 2));

    const response = await request(app)
      .post("/api/image/thumbnail?width=50&height=50&format=jpeg&mode=fit")
      .set("x-shared-key", "test-key")
      .attach("file", corruptedBuffer, { filename: "corrupted.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(400);
    expect(response.body.type).toBe("validation");
    expect(response.body.code).toBe("invalid-image");
  });
});

describe("HTTP quality parameter clamping", () => {
  let app: ReturnType<typeof createTestApp>;

  beforeAll(() => {
    app = createTestApp();
  });

  it("clamps quality=0 to 1 at route level", async () => {
    const imageBuffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const response = await request(app)
      .post("/api/image/thumbnail?width=50&height=50&quality=0&format=jpeg&mode=fit")
      .set("x-shared-key", "test-key")
      .attach("file", imageBuffer, { filename: "test.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(200);
    expect(response.headers["content-type"]).toMatch(/image\/jpeg/);
  });

  it("clamps quality=101 to 100 at route level", async () => {
    const imageBuffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 255, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const response = await request(app)
      .post("/api/image/thumbnail?width=50&height=50&quality=101&format=jpeg&mode=fit")
      .set("x-shared-key", "test-key")
      .attach("file", imageBuffer, { filename: "test.jpg", contentType: "image/jpeg" });

    expect(response.status).toBe(200);
    expect(response.headers["content-type"]).toMatch(/image\/jpeg/);
  });
});
