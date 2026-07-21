import multer from "multer";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { randomBytes } from "node:crypto";
import type { Request } from "express";

interface HybridStorageOptions {
  memoryThreshold: number;
}

interface FileInfo {
  destination: string;
  filename: string;
  path: string;
  size: number;
}

function getContentLength(req: Request): number {
  const cl = req.headers["content-length"];
  if (!cl) return -1;
  const parsed = parseInt(cl, 10);
  return isNaN(parsed) ? -1 : parsed;
}

export function createHybridStorage(opts: HybridStorageOptions): multer.StorageEngine {
  const memoryStorage = multer.memoryStorage();

  let tempDir: string | null = null;

  async function ensureTempDir(): Promise<string> {
    if (!tempDir) {
      tempDir = await mkdtemp(join(tmpdir(), "penpot.upload."));
    }
    return tempDir;
  }

  return {
    _handleFile(req: Request, file: Express.Multer.File, cb: (error?: any, info?: Partial<FileInfo>) => void): void {
      const contentLength = getContentLength(req);
      const useDisk = contentLength >= 0 && contentLength >= opts.memoryThreshold;

      if (!useDisk) {
        memoryStorage._handleFile(req, file, cb);
        return;
      }

      ensureTempDir()
        .then((dir) => {
          const filename = `${randomBytes(16).toString("hex")}${getExt(file.originalname)}`;
          const filepath = join(dir, filename);

          const ws = require("fs").createWriteStream(filepath);

          file.stream.pipe(ws);

          ws.on("error", (err: Error) => {
            cb(err);
          });

          ws.on("finish", () => {
            cb(null, {
              destination: dir,
              filename,
              path: filepath,
              size: ws.bytesWritten,
            });
          });
        })
        .catch(cb);
    },

    _removeFile(req: Request, file: Express.Multer.File & { path?: string }, cb: (error: Error | null) => void): void {
      if (file.path) {
        rm(file.path, { force: true })
          .then(() => cb(null))
          .catch(() => cb(null));
      } else {
        cb(null);
      }
    },
  };
}

function getExt(filename: string): string {
  const dot = filename.lastIndexOf(".");
  return dot >= 0 ? filename.substring(dot) : "";
}
