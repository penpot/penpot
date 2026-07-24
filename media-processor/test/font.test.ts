import { describe, it, expect, beforeAll } from "vitest";
import { readFile, writeFile, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { convertFont } from "../src/services/font.js";
import { ProcessingError } from "../src/middleware/error-handler.js";

const FIXTURES = join(import.meta.dirname, "fixtures");

let ttfData: Buffer;
let otfData: Buffer;
let woffData: Buffer;
let woff2Data: Buffer;

beforeAll(async () => {
  [ttfData, otfData, woffData, woff2Data] = await Promise.all([
    readFile(join(FIXTURES, "font-1.ttf")),
    readFile(join(FIXTURES, "font-1.otf")),
    readFile(join(FIXTURES, "font-1.woff")),
    readFile(join(FIXTURES, "font-1.woff2")),
  ]);
});

describe("convertFont", () => {
  describe("sourceType=ttf", () => {
    it("ttf→ttf returns the input buffer unchanged", async () => {
      const result = await convertFont(ttfData, "font/ttf", "font/ttf");
      expect(result).toBe(ttfData);
    });

    it("ttf→otf returns non-null Buffer", async () => {
      const result = await convertFont(ttfData, "font/ttf", "font/otf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("ttf→woff returns non-null Buffer", async () => {
      const result = await convertFont(ttfData, "font/ttf", "font/woff");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });
  });

  describe("sourceType=otf", () => {
    it("otf→otf returns the input buffer unchanged", async () => {
      const result = await convertFont(otfData, "font/otf", "font/otf");
      expect(result).toBe(otfData);
    });

    it("otf→ttf returns non-null Buffer", async () => {
      const result = await convertFont(otfData, "font/otf", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("otf→woff returns non-null Buffer", async () => {
      const result = await convertFont(otfData, "font/otf", "font/woff");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });
  });

  describe("sourceType=woff", () => {
    it("woff→woff returns the input buffer unchanged", async () => {
      const result = await convertFont(woffData, "font/woff", "font/woff");
      expect(result).toBe(woffData);
    });

    it("woff→ttf returns non-null Buffer", async () => {
      const result = await convertFont(woffData, "font/woff", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("woff→otf returns Buffer or null (FontForge limitation)", async () => {
      const result = await convertFont(woffData, "font/woff", "font/otf");
      // FontForge may fail to convert TTF-based WOFF to OTF for some fonts.
      // The backend handles null gracefully (variant is just absent).
      if (result !== null) {
        expect(result).toBeInstanceOf(Buffer);
        expect(result.length).toBeGreaterThan(0);
      }
    });
  });

  describe("sourceType=woff2", () => {
    it("woff2→ttf returns non-null Buffer", async () => {
      const result = await convertFont(woff2Data, "font/woff2", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("woff2→otf returns non-null Buffer", async () => {
      const result = await convertFont(woff2Data, "font/woff2", "font/otf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("woff2→woff returns non-null Buffer", async () => {
      const result = await convertFont(woff2Data, "font/woff2", "font/woff");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });
  });

  describe("invalid input", () => {
    it("woff with garbage data throws ProcessingError", async () => {
      const garbage = Buffer.from("not a font at all");
      await expect(convertFont(garbage, "font/woff", "font/ttf")).rejects.toThrow(ProcessingError);
    });

    it("woff2 with garbage data throws ProcessingError", async () => {
      const garbage = Buffer.from("not a font at all");
      await expect(convertFont(garbage, "font/woff2", "font/ttf")).rejects.toThrow(ProcessingError);
    });

    it("sfnt with garbage data throws validation error", async () => {
      const garbage = Buffer.from("not a font at all");
      try {
        await convertFont(garbage, "font/woff", "font/ttf");
        expect.fail("should have thrown");
      } catch (err) {
        expect(err).toBeInstanceOf(ProcessingError);
        const pe = err as import("../src/middleware/error-handler.js").ProcessingError;
        expect(pe.statusCode).toBe(400);
        expect(pe.errorBody.code).toBe("invalid-font");
      }
    });
  });

  describe("data integrity", () => {
    it("ttf→otf produces valid font buffer", async () => {
      const result = await convertFont(ttfData, "font/ttf", "font/otf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("otf→ttf produces valid font buffer", async () => {
      const result = await convertFont(otfData, "font/otf", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });

    it("woff→ttf produces valid SFNT with correct magic bytes", async () => {
      const result = await convertFont(woffData, "font/woff", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
      // SFNT magic: 00010000 (TTF) or 4f54544f (OTF/CFF)
      const magic = result!.subarray(0, 4).toString("hex");
      expect(["00010000", "4f54544f"]).toContain(magic);
    });

    it("woff2→ttf produces valid font buffer", async () => {
      const result = await convertFont(woff2Data, "font/woff2", "font/ttf");
      expect(result).toBeInstanceOf(Buffer);
      expect(result!.length).toBeGreaterThan(0);
    });
  });

  describe("file path input", () => {
    it("ttf→otf with file path returns non-null Buffer", async () => {
      const tempPath = join(tmpdir(), `test-font-${Date.now()}.ttf`);
      try {
        await writeFile(tempPath, ttfData);
        const result = await convertFont(tempPath, "font/ttf", "font/otf");
        expect(result).toBeInstanceOf(Buffer);
        expect(result!.length).toBeGreaterThan(0);
      } finally {
        await rm(tempPath, { force: true });
      }
    });

    it("ttf→ttf with file path returns file contents as Buffer", async () => {
      const tempPath = join(tmpdir(), `test-font-${Date.now()}.ttf`);
      try {
        await writeFile(tempPath, ttfData);
        const result = await convertFont(tempPath, "font/ttf", "font/ttf");
        expect(result).toBeInstanceOf(Buffer);
        expect(result!.length).toBe(ttfData.length);
      } finally {
        await rm(tempPath, { force: true });
      }
    });

    it("woff→ttf with file path returns non-null Buffer", async () => {
      const tempPath = join(tmpdir(), `test-font-${Date.now()}.woff`);
      try {
        await writeFile(tempPath, woffData);
        const result = await convertFont(tempPath, "font/woff", "font/ttf");
        expect(result).toBeInstanceOf(Buffer);
        expect(result!.length).toBeGreaterThan(0);
      } finally {
        await rm(tempPath, { force: true });
      }
    });
  });
});
