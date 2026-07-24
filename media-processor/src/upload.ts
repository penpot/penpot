import multer from "multer";
import { readFile } from "node:fs/promises";
import { createHybridStorage } from "./upload-storage.js";
import type { Request } from "express";
import type { FileInput } from "./types.js";

let _upload: multer.Multer | null = null;

// Hybrid storage: small uploads (< memoryThreshold) buffered in RAM for speed;
// large uploads streamed to disk to avoid heap pressure.
// Default threshold is 10MB. Disk files are cleaned up after response finishes.
export function configureUploadLimits(opts: { maxFileSize: number; memoryThreshold: number }): void {
  const storage = createHybridStorage({ memoryThreshold: opts.memoryThreshold });

  _upload = multer({
    storage,
    limits: { fileSize: opts.maxFileSize },
  });
}

export function getUpload(): multer.Multer {
  if (!_upload) {
    throw new Error("Upload not configured — call configureUploadLimits first");
  }
  return _upload;
}

// Returns file input suitable for sharp and font processing.
// For disk-stored files, returns the file path (libvips uses mmap).
// For memory-stored files, returns the buffer.
export function getFileInput(file: Express.Multer.File): FileInput {
  if (file.path) {
    return file.path;
  }
  if (file.buffer) {
    return file.buffer;
  }
  throw new Error("File has no buffer or path");
}

// Returns file contents as Buffer regardless of storage backend (memory or disk).
export async function getFileBuffer(file: Express.Multer.File): Promise<Buffer> {
  if (file.buffer) {
    return file.buffer;
  }
  if (file.path) {
    return readFile(file.path);
  }
  throw new Error("File has no buffer or path");
}
