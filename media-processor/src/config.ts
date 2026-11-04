import { z } from "zod";
import { hkdfSync } from "node:crypto";
import type { AppConfig } from "./types.js";

/**
 * Configuration of the media worker, read from the environment.
 *
 * The worker talks to three things: Redis (the media job queues), the
 * backend (job ledger, input download and result upload) and its own
 * health endpoint. `PORT`/`HOST` are only for the health endpoint.
 */
const envSchema = z.object({
  PENPOT_MEDIA_PROCESSOR_PORT: z.coerce.number().int().positive().default(6065),
  PENPOT_MEDIA_PROCESSOR_HOST: z.string().default("0.0.0.0"),
  PENPOT_MEDIA_PROCESSOR_JOBS_FAST_QUEUE: z.string().min(1).default("media-fast"),
  PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE: z.string().min(1).default("media"),
  PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY: z.coerce.number().int().min(1).default(2),
  PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY: z.coerce.number().int().min(1).default(2),
  PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE: z.coerce.number().int().positive().default(367001600), // 350 MB
  PENPOT_MEDIA_PROCESSOR_REDIS_URI: z.string().min(1).default("redis://redis/0"),
  PENPOT_MEDIA_PROCESSOR_TENANT: z.string().min(1).default("default"),
  PENPOT_MEDIA_PROCESSOR_BACKEND_URI: z.string().url().optional(),
  PENPOT_MEDIA_PROCESSOR_BACKEND_TIMEOUT: z.coerce.number().int().positive().default(60000),
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_PIXELS: z.coerce.number().int().positive().default(128_000_000),
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_WIDTH: z.coerce.number().int().positive().default(16384),
  PENPOT_MEDIA_PROCESSOR_IMAGE_MAX_HEIGHT: z.coerce.number().int().positive().default(16384),
  PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_MEM: z.coerce.number().int().positive().default(512),
  PENPOT_MEDIA_PROCESSOR_FONT_PROCESS_CPU_TIME: z.coerce.number().int().positive().default(30),
  PENPOT_MEDIA_PROCESSOR_FONT_TIMEOUT: z.coerce.number().int().positive().default(120000),
  PENPOT_MEDIA_PROCESSOR_SHARED_KEY: z.string().optional(),
  PENPOT_SECRET_KEY: z.string().optional(),
  PENPOT_MEDIA_PROCESSOR_LOG_LEVEL: z
    .enum(["fatal", "error", "warn", "info", "debug", "trace", "silent"])
    .default("info"),
  PENPOT_LOGGERS_LOKI_URI: z.string().optional(),
  PENPOT_LOGGERS_LOKI_JOB: z.string().default("media-processor"),
  PENPOT_LOGGERS_LOKI_ENVIRONMENT: z.string().optional(),
  PENPOT_LOGGERS_LOKI_INSTANCE: z.string().optional(),
});

export function deriveSharedKey(secret: string): string {
  const key = hkdfSync("blake2b512", secret, Buffer.from("media-processor"), "", 32);
  return Buffer.from(key).toString("base64url");
}

const logEnvSchema = envSchema.pick({
  PENPOT_MEDIA_PROCESSOR_LOG_LEVEL: true,
  PENPOT_LOGGERS_LOKI_URI: true,
  PENPOT_LOGGERS_LOKI_JOB: true,
  PENPOT_LOGGERS_LOKI_ENVIRONMENT: true,
  PENPOT_LOGGERS_LOKI_INSTANCE: true,
});

export type LogConfig = Pick<AppConfig, "logLevel" | "lokiUri" | "lokiJob" | "lokiEnvironment" | "lokiInstance">;

/**
 * The subset the logger needs. It is deliberately separate from
 * `loadConfig`: importing a service must not require the worker's Redis or
 * backend settings, and the logger is created before the worker boots.
 */
export function loadLogConfig(env: NodeJS.ProcessEnv = process.env): LogConfig {
  const parsed = logEnvSchema.parse(env);
  return {
    logLevel: parsed.PENPOT_MEDIA_PROCESSOR_LOG_LEVEL,
    lokiUri: parsed.PENPOT_LOGGERS_LOKI_URI || null,
    lokiJob: parsed.PENPOT_LOGGERS_LOKI_JOB,
    lokiEnvironment: parsed.PENPOT_LOGGERS_LOKI_ENVIRONMENT || null,
    lokiInstance: parsed.PENPOT_LOGGERS_LOKI_INSTANCE || null,
  };
}

export function loadConfig(): AppConfig {
  const parsed = envSchema.parse(process.env);

  let sharedKey: string | null = null;
  if (parsed.PENPOT_MEDIA_PROCESSOR_SHARED_KEY) {
    sharedKey = parsed.PENPOT_MEDIA_PROCESSOR_SHARED_KEY;
  } else if (parsed.PENPOT_SECRET_KEY) {
    sharedKey = deriveSharedKey(parsed.PENPOT_SECRET_KEY);
  }

  if (!sharedKey) {
    throw new Error("missing media-processor shared key: set PENPOT_MEDIA_PROCESSOR_SHARED_KEY or PENPOT_SECRET_KEY");
  }

  if (!parsed.PENPOT_MEDIA_PROCESSOR_BACKEND_URI) {
    throw new Error("missing PENPOT_MEDIA_PROCESSOR_BACKEND_URI: the worker needs the backend URI");
  }

  return {
    port: parsed.PENPOT_MEDIA_PROCESSOR_PORT,
    host: parsed.PENPOT_MEDIA_PROCESSOR_HOST,
    jobsFastQueue: parsed.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_QUEUE,
    jobsQueue: parsed.PENPOT_MEDIA_PROCESSOR_JOBS_QUEUE,
    jobsFastConcurrency: parsed.PENPOT_MEDIA_PROCESSOR_JOBS_FAST_CONCURRENCY,
    jobsConcurrency: parsed.PENPOT_MEDIA_PROCESSOR_JOBS_CONCURRENCY,
    maxFileSize: parsed.PENPOT_MEDIA_PROCESSOR_MAX_FILE_SIZE,
    redisUri: parsed.PENPOT_MEDIA_PROCESSOR_REDIS_URI,
    tenant: parsed.PENPOT_MEDIA_PROCESSOR_TENANT,
    backendUri: parsed.PENPOT_MEDIA_PROCESSOR_BACKEND_URI,
    backendTimeout: parsed.PENPOT_MEDIA_PROCESSOR_BACKEND_TIMEOUT,
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
