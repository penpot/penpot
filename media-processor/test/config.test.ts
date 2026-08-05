import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { loadConfig } from "../src/config.js";

describe("loadConfig", () => {
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = { ...originalEnv };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it("uses defaults when env vars not set", () => {
    const config = loadConfig();
    expect(config.port).toBe(6065);
    expect(config.host).toBe("0.0.0.0");
    expect(config.maxConcurrentRequests).toBe(10);
    expect(config.requestTimeout).toBe(180000);
    expect(config.maxFileSize).toBe(367001600);
    expect(config.memoryThreshold).toBe(10485760);
  });

  it("accepts valid config with all fields set", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "8080";
    process.env.PENPOT_MEDIA_PROCESSOR_HOST = "127.0.0.1";
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "20";
    process.env.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT = "30000";
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE = "104857600";
    process.env.PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD = "5242880";
    process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY = "test-key";

    const config = loadConfig();
    expect(config.port).toBe(8080);
    expect(config.host).toBe("127.0.0.1");
    expect(config.maxConcurrentRequests).toBe(20);
    expect(config.requestTimeout).toBe(30000);
    expect(config.maxFileSize).toBe(104857600);
    expect(config.memoryThreshold).toBe(5242880);
    expect(config.sharedKey).toBe("test-key");
  });

  it("rejects concurrency=0", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "0";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative concurrency", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "-5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional concurrency", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "2.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative timeout", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT = "-1000";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional timeout", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT = "1000.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional port", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "8080.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative port", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "-8080";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative max file size", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE = "-100";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative memory threshold", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD = "-100";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative image max pixels", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS = "-100";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional image max width", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH = "100.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional image max height", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT = "100.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font process mem", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM = "-512";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font process cpu time", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME = "-30";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font timeout", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT = "-120000";
    expect(() => loadConfig()).toThrow();
  });

  it("accepts concurrency=1 (minimum valid)", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "1";
    const config = loadConfig();
    expect(config.maxConcurrentRequests).toBe(1);
  });

  it("accepts timeout=0 (edge case, might be valid for testing)", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT = "0";
    const config = loadConfig();
    expect(config.requestTimeout).toBe(0);
  });
});
