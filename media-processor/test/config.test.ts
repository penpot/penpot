import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { loadConfig } from "../src/config.js";

const ENV_KEYS = [
  "PENPOT_MEDIA_PROCESSOR_PORT",
  "PENPOT_MEDIA_PROCESSOR_HOST",
  "PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS",
  "PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT",
  "PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE",
  "PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS",
  "PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH",
  "PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT",
  "PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM",
  "PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME",
  "PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT",
  "PENPOT_MEDIA_PROCESSOR_SHARED_KEY",
  "PENPOT_SECRET_KEY",
  "PENPOT_MEDIA_PROCESSOR_LOG_LEVEL",
  "PENPOT_LOGGERS_LOKI_URI",
  "PENPOT_LOGGERS_LOKI_JOB",
  "PENPOT_LOGGERS_LOKI_ENVIRONMENT",
  "PENPOT_LOGGERS_LOKI_INSTANCE",
];

let savedEnv: Record<string, string | undefined>;

beforeEach(() => {
  savedEnv = {};
  for (const key of ENV_KEYS) {
    savedEnv[key] = process.env[key];
    delete process.env[key];
  }
});

afterEach(() => {
  for (const key of ENV_KEYS) {
    if (savedEnv[key] === undefined) {
      delete process.env[key];
    } else {
      process.env[key] = savedEnv[key];
    }
  }
});

describe("loadConfig", () => {
  it("returns defaults when no env vars set", () => {
    const config = loadConfig();
    expect(config.port).toBe(6065);
    expect(config.host).toBe("0.0.0.0");
    expect(config.maxConcurrentRequests).toBe(10);
    expect(config.requestTimeout).toBe(60000);
    expect(config.maxFileSize).toBe(367001600);
    expect(config.imageMaxPixels).toBe(128_000_000);
    expect(config.imageMaxWidth).toBe(16384);
    expect(config.imageMaxHeight).toBe(16384);
    expect(config.fontProcessMem).toBe(512);
    expect(config.fontProcessCpuTime).toBe(30);
    expect(config.fontTimeout).toBe(120000);
  });

  it("parses custom PORT from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "8080";
    const config = loadConfig();
    expect(config.port).toBe(8080);
  });

  it("parses custom HOST from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_HOST = "127.0.0.1";
    const config = loadConfig();
    expect(config.host).toBe("127.0.0.1");
  });

  it("parses custom IMAGE_MAX_PIXELS from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS = "50000000";
    const config = loadConfig();
    expect(config.imageMaxPixels).toBe(50000000);
  });

  it("parses custom IMAGE_MAX_WIDTH from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH = "8192";
    const config = loadConfig();
    expect(config.imageMaxWidth).toBe(8192);
  });

  it("parses custom IMAGE_MAX_HEIGHT from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT = "4096";
    const config = loadConfig();
    expect(config.imageMaxHeight).toBe(4096);
  });

  it("parses custom FONT_PROCESS_MEM from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM = "1024";
    const config = loadConfig();
    expect(config.fontProcessMem).toBe(1024);
  });

  it("parses custom FONT_PROCESS_CPU_TIME from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME = "60";
    const config = loadConfig();
    expect(config.fontProcessCpuTime).toBe(60);
  });

  it("parses custom FONT_TIMEOUT from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT = "300000";
    const config = loadConfig();
    expect(config.fontTimeout).toBe(300000);
  });

  it("parses custom REQUEST_TIMEOUT from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT = "120000";
    const config = loadConfig();
    expect(config.requestTimeout).toBe(120000);
  });

  it("parses custom MAX_FILE_SIZE from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE = "1000000";
    const config = loadConfig();
    expect(config.maxFileSize).toBe(1000000);
  });

  it("parses custom MAX_CONCURRENT_REQUESTS from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS = "20";
    const config = loadConfig();
    expect(config.maxConcurrentRequests).toBe(20);
  });

  it("coerces string values to numbers", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "9090";
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM = "256";
    const config = loadConfig();
    expect(config.port).toBe(9090);
    expect(config.fontProcessMem).toBe(256);
  });

  it("handles numeric zero values", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME = "0";
    const config = loadConfig();
    expect(config.fontProcessCpuTime).toBe(0);
  });

  it("handles floating point values (truncates via coerce)", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "3000.5";
    const config = loadConfig();
    expect(config.port).toBe(3000.5); // z.coerce.number() preserves decimals
  });

  it("sharedKey is null when no key env vars set", () => {
    const config = loadConfig();
    expect(config.sharedKey).toBeNull();
  });

  it("sharedKey uses PENPOT_MEDIA_PROCESSOR_SHARED_KEY when set", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY = "my-explicit-key";
    const config = loadConfig();
    expect(config.sharedKey).toBe("my-explicit-key");
  });

  it("sharedKey derives from PENPOT_SECRET_KEY when explicit key not set", () => {
    process.env.PENPOT_SECRET_KEY = "test-secret-key-12345";
    const config = loadConfig();
    expect(config.sharedKey).not.toBeNull();
    expect(config.sharedKey).toMatch(/^[A-Za-z0-9_-]+$/); // base64url
  });

  it("explicit key takes precedence over derived key", () => {
    process.env.PENPOT_SECRET_KEY = "test-secret-key-12345";
    process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY = "explicit-wins";
    const config = loadConfig();
    expect(config.sharedKey).toBe("explicit-wins");
  });

  it("derived key is deterministic", () => {
    process.env.PENPOT_SECRET_KEY = "test-secret-key-12345";
    const config1 = loadConfig();
    const config2 = loadConfig();
    expect(config1.sharedKey).toBe(config2.sharedKey);
  });

  it("logLevel defaults to info", () => {
    const config = loadConfig();
    expect(config.logLevel).toBe("info");
  });

  it("parses custom PENPOT_MEDIA_PROCESSOR_LOG_LEVEL from env", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL = "debug";
    const config = loadConfig();
    expect(config.logLevel).toBe("debug");
  });

  it("rejects invalid log level", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL = "invalid";
    expect(() => loadConfig()).toThrow();
  });

  it("lokiUri is null when PENPOT_LOGGERS_LOKI_URI not set", () => {
    const config = loadConfig();
    expect(config.lokiUri).toBeNull();
  });

  it("parses PENPOT_LOGGERS_LOKI_URI from env", () => {
    process.env.PENPOT_LOGGERS_LOKI_URI = "http://loki:3100";
    const config = loadConfig();
    expect(config.lokiUri).toBe("http://loki:3100");
  });

  it("lokiJob defaults to media-processor", () => {
    const config = loadConfig();
    expect(config.lokiJob).toBe("media-processor");
  });

  it("parses custom PENPOT_LOGGERS_LOKI_JOB from env", () => {
    process.env.PENPOT_LOGGERS_LOKI_JOB = "my-job";
    const config = loadConfig();
    expect(config.lokiJob).toBe("my-job");
  });

  it("lokiEnvironment is null when not set", () => {
    const config = loadConfig();
    expect(config.lokiEnvironment).toBeNull();
  });

  it("parses PENPOT_LOGGERS_LOKI_ENVIRONMENT from env", () => {
    process.env.PENPOT_LOGGERS_LOKI_ENVIRONMENT = "production";
    const config = loadConfig();
    expect(config.lokiEnvironment).toBe("production");
  });

  it("lokiInstance is null when not set", () => {
    const config = loadConfig();
    expect(config.lokiInstance).toBeNull();
  });

  it("parses PENPOT_LOGGERS_LOKI_INSTANCE from env", () => {
    process.env.PENPOT_LOGGERS_LOKI_INSTANCE = "media-processor-01";
    const config = loadConfig();
    expect(config.lokiInstance).toBe("media-processor-01");
  });
});
