export type FileInput = Buffer | string;

export interface AppConfig {
  port: number;
  host: string;
  maxConcurrentRequests: number;
  requestTimeout: number;
  maxFileSize: number;
  memoryThreshold: number;
  imageMaxPixels: number;
  imageMaxWidth: number;
  imageMaxHeight: number;
  fontProcessMem: number;
  fontProcessCpuTime: number;
  fontTimeout: number;
  sharedKey: string | null;
  logLevel: string;
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

export interface AppError {
  type: "validation" | "restriction" | "internal";
  code: string;
  hint?: string;
}
