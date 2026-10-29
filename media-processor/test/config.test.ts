import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { loadConfig, deriveSharedKey } from "../src/config.js";

const backendUri = "http://backend:6060";

describe("loadConfig", () => {
  const originalEnv = process.env;

  beforeEach(() => {
    process.env = {
      ...originalEnv,
      PENPOT_MEDIA_PROCESSOR_BACKEND_URI: backendUri,
      PENPOT_MEDIA_PROCESSOR_SHARED_KEY: "test-key",
    };
  });

  afterEach(() => {
    process.env = originalEnv;
  });

  it("uses defaults when optional env vars are not set", () => {
    const config = loadConfig();
    expect(config.port).toBe(6065);
    expect(config.host).toBe("0.0.0.0");
    expect(config.jobsFastQueue).toBe("media-fast");
    expect(config.jobsQueue).toBe("media");
    expect(config.jobsFastConcurrency).toBe(2);
    expect(config.jobsConcurrency).toBe(2);
    expect(config.maxFileSize).toBe(367001600);
    expect(config.svgMaxSize).toBe(2097152);
    expect(config.svgWorkers).toBe(2);
    expect(config.svgWorkerMaxOldMb).toBe(512);
    expect(config.svgTimeout).toBe(30000);
    expect(config.redisUri).toBe("redis://redis/0");
    expect(config.tenant).toBe("default");
    expect(config.backendUri).toBe(backendUri);
    expect(config.backendTimeout).toBe(60000);
    expect(config.sharedKey).toBe("test-key");
  });

  it("accepts valid config with all fields set", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_PORT = "8080";
    process.env.PENPOT_MEDIA_PROCESSOR_HOST = "127.0.0.1";
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_QUEUE = "media-fast-eu";
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE = "media-eu";
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY = "4";
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY = "3";
    process.env.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE = "104857600";
    process.env.PENPOT_MEDIA_PROCESSOR_SVG_MAX_SIZE = "1024";
    process.env.PENPOT_MEDIA_PROCESSOR_SVG_WORKERS = "0";
    process.env.PENPOT_MEDIA_PROCESSOR_SVG_WORKER_MAX_OLD_MB = "256";
    process.env.PENPOT_MEDIA_PROCESSOR_SVG_TIMEOUT = "1000";
    process.env.PENPOT_MEDIA_PROCESSOR_REDIS_URI = "redis://cache:6379/2";
    process.env.PENPOT_MEDIA_PROCESSOR_TENANT = "acme";
    process.env.PENPOT_MEDIA_PROCESSOR_BACKEND_URI = "https://penpot.internal";
    process.env.PENPOT_MEDIA_PROCESSOR_BACKEND_TIMEOUT = "30000";
    process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY = "other-key";

    const config = loadConfig();
    expect(config.port).toBe(8080);
    expect(config.host).toBe("127.0.0.1");
    expect(config.jobsFastQueue).toBe("media-fast-eu");
    expect(config.jobsQueue).toBe("media-eu");
    expect(config.jobsFastConcurrency).toBe(4);
    expect(config.jobsConcurrency).toBe(3);
    expect(config.maxFileSize).toBe(104857600);
    expect(config.svgMaxSize).toBe(1024);
    expect(config.svgWorkers).toBe(0);
    expect(config.svgWorkerMaxOldMb).toBe(256);
    expect(config.svgTimeout).toBe(1000);
    expect(config.redisUri).toBe("redis://cache:6379/2");
    expect(config.tenant).toBe("acme");
    expect(config.backendUri).toBe("https://penpot.internal");
    expect(config.backendTimeout).toBe(30000);
    expect(config.sharedKey).toBe("other-key");
  });

  it("derives the shared key from the secret when no key is set", () => {
    delete process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY;
    process.env.PENPOT_SECRET_KEY = "test-secret";

    const config = loadConfig();
    expect(config.sharedKey).toBe("LdlMV3QZ9ECeKJyP4cyGQLFdj4Z7PcwuLZOT0CKsxYk");
    expect(config.sharedKey).toBe(deriveSharedKey("test-secret"));
  });

  it("rejects a missing backend uri", () => {
    delete process.env.PENPOT_MEDIA_PROCESSOR_BACKEND_URI;
    expect(() => loadConfig()).toThrow(/BACKEND_URI/);
  });

  it("rejects a missing shared key and secret", () => {
    delete process.env.PENPOT_MEDIA_PROCESSOR_SHARED_KEY;
    delete process.env.PENPOT_SECRET_KEY;
    expect(() => loadConfig()).toThrow(/shared key/);
  });

  it("rejects jobs concurrency=0", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY = "0";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative jobs concurrency", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY = "-5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional fast jobs concurrency", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY = "2.5";
    expect(() => loadConfig()).toThrow();
  });

  it("rejects an empty queue name", () => {
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE = "";
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
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY = "1";
    process.env.PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY = "1";
    const config = loadConfig();
    expect(config.jobsFastConcurrency).toBe(1);
    expect(config.jobsConcurrency).toBe(1);
  });
});
