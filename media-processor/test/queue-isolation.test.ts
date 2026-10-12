import { describe, it, expect, beforeAll, afterAll } from "vitest";
import request from "supertest";
import sharp from "sharp";
import { EventEmitter } from "node:events";
import { createApp } from "../src/app.js";
import { configureUploadLimits } from "../src/upload.js";
import { configureSvgPool, shutdownSvgPool, setSvgWorkerFactory, type SvgWorker } from "../src/svg-pool.js";

// A worker that never answers, so an SVG request parks on the pool while it
// holds its queue slot.
class HangingWorker extends EventEmitter {
  public postMessage(): void {}
  public async terminate(): Promise<number> {
    this.emit("exit", 0);
    return 0;
  }
}

const config = { sharedKey: "test-key", maxConcurrentRequests: 1, requestTimeout: 10000 };

describe("queue isolation", () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(() => {
    configureUploadLimits({ maxFileSize: 10 * 1024 * 1024, memoryThreshold: 10 });
    setSvgWorkerFactory(() => new HangingWorker() as unknown as SvgWorker);
    configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 5000 });
    app = createApp(config);
  });

  afterAll(async () => {
    await shutdownSvgPool();
    setSvgWorkerFactory(null);
  });

  it("a pending SVG request does not block an image request", async () => {
    const svgRequest = request(app)
      .post("/api/svg/sanitize")
      .set("x-shared-key", "test-key")
      .attach("file", Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>`), {
        filename: "x.svg",
        contentType: "image/svg+xml",
      })
      .then((res) => res.status)
      .catch(() => 0);

    // Let the SVG request be admitted and reach the hanging worker.
    await new Promise((resolve) => setTimeout(resolve, 100));

    const image = await sharp({
      create: { width: 10, height: 10, channels: 3, background: { r: 1, g: 2, b: 3 } },
    })
      .jpeg()
      .toBuffer();

    const imageRequest = request(app)
      .post("/api/image/info")
      .set("x-shared-key", "test-key")
      .attach("file", image, { filename: "i.jpg", contentType: "image/jpeg" })
      .then((res) => res.status)
      .catch(() => 0);

    const outcome = await Promise.race([
      imageRequest,
      new Promise<"timeout">((resolve) => setTimeout(() => resolve("timeout"), 2000)),
    ]);

    expect(outcome).toBe(200);

    // Release the held SVG request so the test ends clean.
    await shutdownSvgPool();
    await svgRequest;
  });
});
