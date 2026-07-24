import { describe, it, expect, afterEach } from "vitest";
import { getImageInfo, generateThumbnail, configureImageLimits } from "../src/services/image.js";
import { ProcessingError } from "../src/middleware/error-handler.js";
import sharp from "sharp";
import { writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";

const DEFAULT_LIMITS = {
  maxPixels: 128_000_000,
  maxWidth: 16384,
  maxHeight: 16384,
};

afterEach(() => {
  configureImageLimits(DEFAULT_LIMITS);
});

describe("getImageInfo", () => {
  it("returns correct info for a PNG", async () => {
    const buffer = await sharp({
      create: { width: 100, height: 80, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 1 } },
    })
      .png()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.width).toBe(100);
    expect(info.height).toBe(80);
    expect(info.mtype).toBe("image/png");
    expect(info.size).toBe(buffer.length);
    expect(info.orientation).toBe(1);
  });

  it("returns correct info for a JPEG", async () => {
    const buffer = await sharp({
      create: { width: 200, height: 150, channels: 3, background: { r: 0, g: 255, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.width).toBe(200);
    expect(info.height).toBe(150);
    expect(info.mtype).toBe("image/jpeg");
  });

  it("returns correct info for a WebP", async () => {
    const buffer = await sharp({
      create: { width: 300, height: 250, channels: 3, background: { r: 0, g: 0, b: 255 } },
    })
      .webp()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.width).toBe(300);
    expect(info.height).toBe(250);
    expect(info.mtype).toBe("image/webp");
  });

  it("throws on invalid image data", async () => {
    const buffer = Buffer.from("not an image");
    await expect(getImageInfo(buffer, buffer.length)).rejects.toThrow();
  });

  it("returns correct info for a GIF", async () => {
    // sharp create doesn't support GIF directly, so create PNG then convert
    const pngBuffer = await sharp({
      create: { width: 120, height: 90, channels: 3, background: { r: 200, g: 100, b: 50 } },
    })
      .png()
      .toBuffer();

    const gifBuffer = await sharp(pngBuffer).gif().toBuffer();
    const info = await getImageInfo(gifBuffer, gifBuffer.length);
    expect(info.width).toBe(120);
    expect(info.height).toBe(90);
    expect(info.mtype).toBe("image/gif");
  });

  it("returns size equal to buffer length", async () => {
    const buffer = await sharp({
      create: { width: 50, height: 50, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.size).toBe(buffer.length);
  });

  it("defaults orientation to 1 when no EXIF data", async () => {
    const buffer = await sharp({
      create: { width: 60, height: 40, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .png()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.orientation).toBe(1);
  });

  it("throws on garbage data (sharp unsupported format)", async () => {
    const buffer = Buffer.alloc(100, 0xff);
    await expect(getImageInfo(buffer, buffer.length)).rejects.toThrow();
  });

  it("accepts file path input and returns correct info", async () => {
    const buffer = await sharp({
      create: { width: 100, height: 80, channels: 3, background: { r: 0, g: 128, b: 255 } },
    })
      .jpeg()
      .toBuffer();

    const tempPath = join(tmpdir(), `test-image-${Date.now()}.jpg`);
    try {
      await writeFile(tempPath, buffer);
      const info = await getImageInfo(tempPath, buffer.length);
      expect(info.width).toBe(100);
      expect(info.height).toBe(80);
      expect(info.mtype).toBe("image/jpeg");
      expect(info.size).toBe(buffer.length);
    } finally {
      await rm(tempPath, { force: true });
    }
  });

  it("throws restriction when width exceeds limit", async () => {
    configureImageLimits({ maxPixels: 128_000_000, maxWidth: 100, maxHeight: 16384 });
    const buffer = await sharp({
      create: { width: 200, height: 50, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    try {
      await getImageInfo(buffer, buffer.length);
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
      expect(pe.statusCode).toBe(413);
      expect(pe.errorBody.code).toBe("image-dimensions-exceeded");
    }
  });

  it("throws restriction when height exceeds limit", async () => {
    configureImageLimits({ maxPixels: 128_000_000, maxWidth: 16384, maxHeight: 100 });
    const buffer = await sharp({
      create: { width: 50, height: 200, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    try {
      await getImageInfo(buffer, buffer.length);
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
      expect(pe.statusCode).toBe(413);
      expect(pe.errorBody.code).toBe("image-dimensions-exceeded");
    }
  });

  it("throws restriction when pixel count exceeds limit", async () => {
    configureImageLimits({ maxPixels: 1000, maxWidth: 16384, maxHeight: 16384 });
    const buffer = await sharp({
      create: { width: 50, height: 50, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    try {
      await getImageInfo(buffer, buffer.length);
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
      expect(pe.statusCode).toBe(413);
      expect(pe.errorBody.code).toBe("image-pixel-count-exceeded");
    }
  });

  it("passes when dimensions are exactly at the limit", async () => {
    configureImageLimits({ maxPixels: 10000, maxWidth: 100, maxHeight: 100 });
    const buffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    const info = await getImageInfo(buffer, buffer.length);
    expect(info.width).toBe(100);
    expect(info.height).toBe(100);
  });

  it("throws when pixel count is exactly 1 over limit", async () => {
    configureImageLimits({ maxPixels: 9999, maxWidth: 16384, maxHeight: 16384 });
    const buffer = await sharp({
      create: { width: 100, height: 100, channels: 3, background: { r: 0, g: 0, b: 0 } },
    })
      .jpeg()
      .toBuffer();

    try {
      await getImageInfo(buffer, buffer.length);
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
      expect(pe.errorBody.code).toBe("image-pixel-count-exceeded");
    }
  });
});

describe("generateThumbnail", () => {
  const createImage = (w: number, h: number) =>
    sharp({
      create: { width: w, height: h, channels: 3, background: { r: 128, g: 128, b: 128 } },
    })
      .jpeg()
      .toBuffer();

  it("mode=fit produces thumbnail fitting within dimensions (no upscale)", async () => {
    const buffer = await createImage(1000, 800);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBeLessThanOrEqual(200);
    expect(meta.height).toBeLessThanOrEqual(200);
    expect(mtype).toBe("image/jpeg");
  });

  it("mode=crop produces center-cropped thumbnail at exact dimensions", async () => {
    const buffer = await createImage(1000, 800);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "crop",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(200);
    expect(meta.height).toBe(200);
    expect(mtype).toBe("image/jpeg");
  });

  it("supports webp output", async () => {
    const buffer = await createImage(500, 400);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 100,
      height: 100,
      quality: 80,
      format: "webp",
      mode: "fit",
    });
    expect(mtype).toBe("image/webp");
    const meta = await sharp(data).metadata();
    expect(meta.width).toBeLessThanOrEqual(100);
  });

  it("supports png output", async () => {
    const buffer = await createImage(500, 400);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 100,
      height: 100,
      quality: 80,
      format: "png",
      mode: "fit",
    });
    expect(mtype).toBe("image/png");
  });

  it("fit mode does not upscale small source", async () => {
    const buffer = await createImage(50, 40);
    const { data } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(50);
    expect(meta.height).toBe(40);
  });

  it("crop mode with non-square target", async () => {
    const buffer = await createImage(1000, 500);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 200,
      height: 100,
      quality: 85,
      format: "jpeg",
      mode: "crop",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(200);
    expect(meta.height).toBe(100);
    expect(mtype).toBe("image/jpeg");
  });

  it("png output with crop mode", async () => {
    const buffer = await createImage(800, 600);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 150,
      height: 150,
      quality: 80,
      format: "png",
      mode: "crop",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(150);
    expect(meta.height).toBe(150);
    expect(mtype).toBe("image/png");
  });

  it("webp output with crop mode", async () => {
    const buffer = await createImage(800, 600);
    const { data, mtype } = await generateThumbnail(buffer, {
      width: 150,
      height: 150,
      quality: 80,
      format: "webp",
      mode: "crop",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(150);
    expect(meta.height).toBe(150);
    expect(mtype).toBe("image/webp");
  });

  it("source at exact target dimensions (fit mode) returns same size", async () => {
    const buffer = await createImage(200, 200);
    const { data } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(200);
    expect(meta.height).toBe(200);
  });

  it("source at exact target dimensions (crop mode) returns same size", async () => {
    const buffer = await createImage(200, 200);
    const { data } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "crop",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(200);
    expect(meta.height).toBe(200);
  });

  it("very small source (1x1) with fit mode returns 1x1", async () => {
    const buffer = await createImage(1, 1);
    const { data } = await generateThumbnail(buffer, {
      width: 200,
      height: 200,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBe(1);
    expect(meta.height).toBe(1);
  });

  it("throws restriction when source exceeds dimension limits", async () => {
    configureImageLimits({ maxPixels: 1000, maxWidth: 50, maxHeight: 50 });
    const buffer = await createImage(200, 200);

    try {
      await generateThumbnail(buffer, {
        width: 100,
        height: 100,
        quality: 85,
        format: "jpeg",
        mode: "fit",
      });
      expect.fail("should have thrown");
    } catch (err) {
      expect(err).toBeInstanceOf(ProcessingError);
      const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
      expect(pe.statusCode).toBe(413);
    }
  });

  it("removes alpha channel from PNG source", async () => {
    const pngBuffer = await sharp({
      create: { width: 100, height: 100, channels: 4, background: { r: 255, g: 0, b: 0, alpha: 0.5 } },
    })
      .png()
      .toBuffer();

    const { data } = await generateThumbnail(pngBuffer, {
      width: 50,
      height: 50,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    // JPEG output should not have alpha
    expect(meta.channels).toBe(3);
  });

  it("composites transparent PNG onto white background for JPEG", async () => {
    // Create a fully transparent PNG — removeAlpha() would produce black,
    // but the local ImageMagick path composites onto white.
    const pngBuffer = await sharp({
      create: { width: 10, height: 10, channels: 4, background: { r: 0, g: 0, b: 0, alpha: 0 } },
    })
      .png()
      .toBuffer();

    const { data } = await generateThumbnail(pngBuffer, {
      width: 10,
      height: 10,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });

    // Sample a pixel — should be white (255,255,255), not black (0,0,0)
    const pixel = await sharp(data).raw().toBuffer();
    const r = pixel[0];
    const g = pixel[1];
    const b = pixel[2];
    expect(r).toBe(255);
    expect(g).toBe(255);
    expect(b).toBe(255);
  });

  it("GIF source works with thumbnail generation", async () => {
    const pngBuffer = await sharp({
      create: { width: 200, height: 200, channels: 3, background: { r: 100, g: 100, b: 100 } },
    })
      .png()
      .toBuffer();

    const gifBuffer = await sharp(pngBuffer).gif().toBuffer();
    const { data, mtype } = await generateThumbnail(gifBuffer, {
      width: 100,
      height: 100,
      quality: 85,
      format: "jpeg",
      mode: "fit",
    });
    const meta = await sharp(data).metadata();
    expect(meta.width).toBeLessThanOrEqual(100);
    expect(meta.height).toBeLessThanOrEqual(100);
    expect(mtype).toBe("image/jpeg");
  });

  it("JPEG quality affects output file size", async () => {
    // Create an image with actual detail (gradient) so quality matters
    const width = 200;
    const height = 200;
    const channels = 3;
    const rawBuffer = Buffer.alloc(width * height * channels);
    for (let y = 0; y < height; y++) {
      for (let x = 0; x < width; x++) {
        const idx = (y * width + x) * channels;
        rawBuffer[idx] = (x * 255) / width;
        rawBuffer[idx + 1] = (y * 255) / height;
        rawBuffer[idx + 2] = ((x + y) * 255) / (width + height);
      }
    }
    const buffer = await sharp(rawBuffer, { raw: { width, height, channels } }).jpeg().toBuffer();

    const low = await generateThumbnail(buffer, {
      width: 100,
      height: 100,
      quality: 10,
      format: "jpeg",
      mode: "fit",
    });
    const high = await generateThumbnail(buffer, {
      width: 100,
      height: 100,
      quality: 100,
      format: "jpeg",
      mode: "fit",
    });
    expect(low.data.length).toBeLessThan(high.data.length);
  });

  it("accepts file path input for thumbnail generation", async () => {
    const buffer = await sharp({
      create: { width: 100, height: 80, channels: 3, background: { r: 128, g: 128, b: 128 } },
    })
      .jpeg()
      .toBuffer();

    const tempPath = join(tmpdir(), `test-image-${Date.now()}.jpg`);
    try {
      await writeFile(tempPath, buffer);
      const { data, mtype } = await generateThumbnail(tempPath, {
        width: 50,
        height: 40,
        quality: 85,
        format: "jpeg",
        mode: "fit",
      });
      const meta = await sharp(data).metadata();
      expect(meta.width).toBeLessThanOrEqual(50);
      expect(meta.height).toBeLessThanOrEqual(40);
      expect(mtype).toBe("image/jpeg");
    } finally {
      await rm(tempPath, { force: true });
    }
  });
});
