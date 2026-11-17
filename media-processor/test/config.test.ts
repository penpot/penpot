import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadConfig } from "../src/config.js";

afterEach(() => vi.unstubAllEnvs());

describe("loadConfig", () => {
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
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_PORT", "8080");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_HOST", "127.0.0.1");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS", "20");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT", "30000");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE", "104857600");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD", "5242880");
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY", "test-key");

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
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS", "0");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative concurrency", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS", "-5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional concurrency", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS", "2.5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative timeout", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT", "-1000");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional timeout", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT", "1000.5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional port", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_PORT", "8080.5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative port", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_PORT", "-8080");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative max file size", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE", "-100");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative memory threshold", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD", "-100");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative image max pixels", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS", "-100");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional image max width", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH", "100.5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects fractional image max height", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT", "100.5");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font process mem", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM", "-512");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font process cpu time", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME", "-30");
    expect(() => loadConfig()).toThrow();
  });

  it("rejects negative font timeout", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT", "-120000");
    expect(() => loadConfig()).toThrow();
  });

  it("accepts concurrency=1 (minimum valid)", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS", "1");
    const config = loadConfig();
    expect(config.maxConcurrentRequests).toBe(1);
  });

  it("accepts timeout=0 (edge case, might be valid for testing)", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT", "0");
    const config = loadConfig();
    expect(config.requestTimeout).toBe(0);
  });
});

describe("shared key file", () => {
  const keyVars = ["PENPOT_MEDIA_PROCESSOR_SHARED_KEY", "PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", "PENPOT_SECRET_KEY"];

  beforeEach(() => {
    for (const variable of keyVars) vi.stubEnv(variable, undefined);
  });

  function withTempDir(f: (dir: string) => void): void {
    const dir = mkdtempSync(join(tmpdir(), "penpot-mp-key-"));
    try {
      f(dir);
    } finally {
      rmSync(dir, { recursive: true, force: true });
    }
  }

  function writeKey(dir: string, content: string): string {
    const path = join(dir, "shared-key");
    writeFileSync(path, content);
    return path;
  }

  it("reads, trims and unwraps the key file", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", writeKey(dir, "from-\n file \tkey"));
      expect(loadConfig().sharedKey).toBe("from-filekey");
    });
  });

  it("prefers the key file over derivation", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_SECRET_KEY", "master");
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", writeKey(dir, "from-file"));
      expect(loadConfig().sharedKey).toBe("from-file");
    });
  });

  it("prefers the explicit key over the file", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY", "explicit");
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", join(dir, "missing"));
      expect(loadConfig().sharedKey).toBe("explicit");
    });
  });

  it("rejects a missing key file with its variable name", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", join(dir, "missing"));
      expect(() => loadConfig()).toThrow(/PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE key file is missing or empty/);
    });
  });

  it("rejects an empty key file", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", writeKey(dir, "  \n\t"));
      expect(() => loadConfig()).toThrow(/missing or empty/);
    });
  });

  it("rejects a directory as key file", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", dir);
      expect(() => loadConfig()).toThrow(/missing or empty/);
    });
  });

  it("rejects an oversized key file", () => {
    withTempDir((dir) => {
      vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE", writeKey(dir, "a".repeat(5000)));
      expect(() => loadConfig()).toThrow(/too large/);
    });
  });

  it("treats a blank explicit key as disabled", () => {
    vi.stubEnv("PENPOT_MEDIA_PROCESSOR_SHARED_KEY", "");
    expect(loadConfig().sharedKey).toBeNull();
  });

  it("keeps sharedKey null when no source is configured", () => {
    expect(loadConfig().sharedKey).toBeNull();
  });
});
