import { z } from "zod";
import { hkdfSync } from "node:crypto";
import { readFileSync, statSync } from "node:fs";
import type { AppConfig } from "./types.js";

const envSchema = z.object({
  PENPOT_MEDIA_PROCESSOR_PORT: z.coerce.number().int().positive().default(6065),
  PENPOT_MEDIA_PROCESSOR_HOST: z.string().default("0.0.0.0"),
  PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS: z.coerce.number().int().min(1).default(10),
  PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT: z.coerce.number().int().nonnegative().default(180000),
  PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE: z.coerce.number().int().positive().default(367001600), // 350 MB
  PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD: z.coerce.number().int().positive().default(10485760), // 10 MB — uploads below this use memory storage; above use disk storage
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS: z.coerce.number().int().positive().default(128_000_000),
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH: z.coerce.number().int().positive().default(16384),
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT: z.coerce.number().int().positive().default(16384),
  PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM: z.coerce.number().int().positive().default(512),
  PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME: z.coerce.number().int().positive().default(30),
  PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT: z.coerce.number().int().positive().default(120000),
  PENPOT_MEDIA_PROCESSOR_SHARED_KEY: z.string().optional(),
  PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE: z.string().optional(),
  PENPOT_SECRET_KEY: z.string().optional(),
  PENPOT_SECRET_KEY_FILE: z.string().optional(),
  PENPOT_MEDIA_PROCESSOR_LOG_LEVEL: z
    .enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"])
    .default("info"),
  PENPOT_LOGGERS_LOKI_URI: z.string().optional(),
  PENPOT_LOGGERS_LOKI_JOB: z.string().default("media-processor"),
  PENPOT_LOGGERS_LOKI_ENVIRONMENT: z.string().optional(),
  PENPOT_LOGGERS_LOKI_INSTANCE: z.string().optional(),
});

function deriveSharedKey(secret: string): string {
  const key = hkdfSync("blake2b512", secret, Buffer.from("media-processor"), "", 32);
  return Buffer.from(key).toString("base64url");
}

const MAX_KEY_FILE_SIZE = 4096;

function readSharedKeyFile(variable: string, path: string): string {
  let info;
  try {
    info = statSync(path);
  } catch (error) {
    console.error("unable to read configuration key file", variable, path, error);
  }
  if (!info || !info.isFile()) {
    console.error("configuration key file is not a regular file", variable, path);
    throw new Error(`${variable} key file is missing or empty: ${path}`);
  }
  if (info.size > MAX_KEY_FILE_SIZE) {
    throw new Error(`${variable} key file is too large: ${path}`);
  }
  let value: string | undefined;
  try {
    value = readFileSync(path, "utf8").trim().replace(/\s+/g, "");
  } catch (error) {
    console.error("unable to read configuration key file", variable, path, error);
  }
  if (!value) {
    throw new Error(`${variable} key file is missing or empty: ${path}`);
  }
  return value;
}

export function loadConfig(): AppConfig {
  const parsed = envSchema.parse(process.env);

  let sharedKey: string | null = null;
  const explicitKey = parsed.PENPOT_MEDIA_PROCESSOR_SHARED_KEY;
  if (explicitKey !== undefined) {
    sharedKey = explicitKey.trim() === "" ? null : explicitKey.trim();
  } else if (parsed.PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE) {
    sharedKey = readSharedKeyFile(
      "PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE",
      parsed.PENPOT_MEDIA_PROCESSOR_SHARED_KEY_FILE
    );
  } else {
    const secret =
      parsed.PENPOT_SECRET_KEY !== undefined
        ? parsed.PENPOT_SECRET_KEY
        : parsed.PENPOT_SECRET_KEY_FILE
          ? readSharedKeyFile("PENPOT_SECRET_KEY_FILE", parsed.PENPOT_SECRET_KEY_FILE)
          : undefined;
    if (secret?.trim()) {
      sharedKey = deriveSharedKey(secret);
    }
  }

  return {
    port: parsed.PENPOT_MEDIA_PROCESSOR_PORT,
    host: parsed.PENPOT_MEDIA_PROCESSOR_HOST,
    maxConcurrentRequests: parsed.PENPOT_MEDIA_PROCESSOR_MAX_CONCURRENT_REQUESTS,
    requestTimeout: parsed.PENPOT_MEDIA_PROCESSOR_REQUEST_TIMEOUT,
    maxFileSize: parsed.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE,
    memoryThreshold: parsed.PENPOT_MEDIA_PROCESSOR_MEMORY_THRESHOLD,
    imageMaxPixels: parsed.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS,
    imageMaxWidth: parsed.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH,
    imageMaxHeight: parsed.PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT,
    fontProcessMem: parsed.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM,
    fontProcessCpuTime: parsed.PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME,
    fontTimeout: parsed.PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT,
    sharedKey,
    logLevel: parsed.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL,
    lokiUri: parsed.PENPOT_LOGGERS_LOKI_URI || null,
    lokiJob: parsed.PENPOT_LOGGERS_LOKI_JOB,
    lokiEnvironment: parsed.PENPOT_LOGGERS_LOKI_ENVIRONMENT || null,
    lokiInstance: parsed.PENPOT_LOGGERS_LOKI_INSTANCE || null,
  };
}
