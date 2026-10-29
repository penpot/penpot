import type { WireError } from "./protocol.js";

export type FileInput = Buffer | string;

export type LogLevel = "fatal" | "error" | "warn" | "info" | "debug" | "trace" | "silent";

export interface AppConfig {
  port: number;
  host: string;
  /** Queue the synchronous media jobs (`media-info`, `profile-thumbnail`) are consumed from. */
  jobsFastQueue: string;
  /** Queue the asynchronous media jobs (`media-thumbnail`, `font-variants`) are consumed from. */
  jobsQueue: string;
  /** Pollers on the fast queue. */
  jobsFastConcurrency: number;
  /** Pollers on the slow queue. */
  jobsConcurrency: number;
  maxFileSize: number;
  imageMaxPixels: number;
  imageMaxWidth: number;
  imageMaxHeight: number;
  svgMaxSize: number;
  svgWorkers: number;
  svgWorkerMaxOldMb: number;
  svgTimeout: number;
  fontProcessMem: number;
  fontProcessCpuTime: number;
  fontTimeout: number;
  redisUri: string;
  tenant: string;
  backendUri: string;
  backendTimeout: number;
  sharedKey: string;
  logLevel: LogLevel;
  lokiUri: string | null;
  lokiJob: string;
  lokiEnvironment: string | null;
  lokiInstance: string | null;
}

export interface ImageInfo {
  width: number;
  height: number;
  mtype: string;
  size: number;
  orientation: number;
}

export interface ThumbnailParams {
  width: number;
  height: number;
  quality: number;
  format: "jpeg" | "webp" | "png";
  mode: "fit" | "crop";
}

export type AppError = WireError;
