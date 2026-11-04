import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { BackendClient, UploadedResult } from "../backend-client.js";
import type { JobContext, JobHandler } from "../jobs-worker.js";
import type { JobProgress, JobsClient } from "../jobs-client.js";
import { ProcessingError } from "../errors.js";
import { createLogger } from "../logger.js";
import {
  fontVariantsJobSchema,
  mediaInfoJobSchema,
  mediaThumbnailJobSchema,
  profileThumbnailJobSchema,
} from "../protocol.js";
import { generateThumbnail, getImageInfo } from "../services/image.js";
import { convertFont } from "../services/font.js";

/**
 * Handlers of the four durable media jobs.
 *
 * They are the remote half of the contract in the backend's
 * `app.media.jobs`: every input is a `tempfile` reference, every result is
 * uploaded to `tempfile` in the same `{id, uri, mtype, size}` shape the
 * local handlers answer, so the backend's terminal hook does not know which
 * executor ran the job.
 *
 * Params are validated with the schemas of `protocol.ts`; a mismatch is
 * `internal/invalid-params` with a generic hint. The processing errors are
 * the ones `image.ts` and `font.ts` already raise.
 */

export interface MediaHandlerOptions {
  backend: BackendClient;
  jobs: JobsClient;
}

const log = createLogger("jobs-media");

const PROFILE_THUMBNAIL = { width: 256, height: 256, quality: 85, format: "jpeg" as const, mode: "crop" as const };
const GENERIC_THUMBNAIL_MODE = "fit" as const;

function invalidParams(cause: unknown): ProcessingError {
  log.warn({ cause }, "invalid job params");
  return new ProcessingError(422, {
    type: "internal",
    code: "invalid-params",
    hint: "invalid job params",
  });
}

function extensionOf(mtype: string): string {
  switch (mtype) {
    case "image/jpeg":
      return "jpg";
    case "image/webp":
      return "webp";
    case "image/png":
      return "png";
    case "font/ttf":
      return "ttf";
    case "font/otf":
      return "otf";
    case "font/woff":
      return "woff";
    case "font/woff2":
      return "woff2";
    default:
      return "bin";
  }
}

async function withWorkdir<T>(fn: (dir: string) => Promise<T>): Promise<T> {
  const dir = await mkdtemp(join(tmpdir(), "penpot-media-job-"));
  try {
    return await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

/**
 * Upload a local artifact and answer the storage reference the backend
 * expects. `upload-tempfile` answers `{id, uri}`; the mtype and size are
 * what the handler already knows, and the result schema needs them.
 */
async function upload(
  backend: BackendClient,
  path: string,
  mtype: string,
  size: number
): Promise<{ id: string; uri: string; mtype: string; size: number }> {
  const uploaded: UploadedResult = await backend.uploadResult(path, {
    mtype,
    filename: `result.${extensionOf(mtype)}`,
  });
  return { id: uploaded.id, uri: uploaded.uri, mtype, size };
}

/**
 * The flow-stopping error of an aborted job. It is never reported: the
 * worker drops the outcome of an aborted handler, because the row is already
 * cancelled or belongs to another attempt.
 */
function jobNotActive(): ProcessingError {
  return new ProcessingError(409, {
    type: "internal",
    code: "job-not-active",
    hint: "the job is no longer active",
  });
}

function assertActive(context: JobContext): void {
  if (context.signal.aborted) {
    throw jobNotActive();
  }
}

async function report(jobs: JobsClient, context: JobContext, progress: JobProgress): Promise<void> {
  const action = await jobs.progress(context.jobId, progress);
  if (action === "skip") {
    // The row is no longer active: abort the job so an in-flight child
    // process dies and nothing else is uploaded, then stop the fan-out.
    context.abort("cancelled");
    throw jobNotActive();
  }
}

export function makeMediaHandlers(options: MediaHandlerOptions): Record<string, JobHandler> {
  const { backend, jobs } = options;

  const mediaInfo: JobHandler = async (params, context) => {
    const parsed = mediaInfoJobSchema.safeParse(params);
    if (!parsed.success) {
      throw invalidParams(parsed.error);
    }
    const { input } = parsed.data;

    return withWorkdir(async (dir) => {
      const path = join(dir, "input");
      await backend.fetchInput(input, path);
      return getImageInfo(path, input.size, context.signal);
    });
  };

  const mediaThumbnail: JobHandler = async (params, context) => {
    const parsed = mediaThumbnailJobSchema.safeParse(params);
    if (!parsed.success) {
      throw invalidParams(parsed.error);
    }
    const { input, format, quality, width, height } = parsed.data;

    return withWorkdir(async (dir) => {
      const path = join(dir, "input");
      await backend.fetchInput(input, path);

      const thumb = await generateThumbnail(
        path,
        {
          width,
          height,
          quality,
          format,
          mode: GENERIC_THUMBNAIL_MODE,
        },
        context.signal
      );
      const outPath = join(dir, "thumbnail");
      await writeFile(outPath, thumb.data);

      assertActive(context);
      return { thumbnail: await upload(backend, outPath, thumb.mtype, thumb.data.length) };
    });
  };

  const profileThumbnail: JobHandler = async (params, context) => {
    const parsed = profileThumbnailJobSchema.safeParse(params);
    if (!parsed.success) {
      throw invalidParams(parsed.error);
    }
    const { input } = parsed.data;

    return withWorkdir(async (dir) => {
      const path = join(dir, "input");
      await backend.fetchInput(input, path);

      const thumb = await generateThumbnail(path, PROFILE_THUMBNAIL, context.signal);
      const outPath = join(dir, "photo");
      await writeFile(outPath, thumb.data);

      assertActive(context);
      return { photo: await upload(backend, outPath, thumb.mtype, thumb.data.length) };
    });
  };

  const fontVariants: JobHandler = async (params, context) => {
    const parsed = fontVariantsJobSchema.safeParse(params);
    if (!parsed.success) {
      throw invalidParams(parsed.error);
    }
    const { input, targets } = parsed.data;
    const sourceMtype = parsed.data.sourceMtype;

    return withWorkdir(async (dir) => {
      const path = join(dir, "input");
      await backend.fetchInput(input, path);

      const variants: Array<{ id: string; uri: string; mtype: string; size: number }> = [];
      for (const [index, target] of targets.entries()) {
        const data = await convertFont(path, sourceMtype, target, context.signal);
        if (data === null) {
          throw new ProcessingError(422, {
            type: "validation",
            code: "invalid-font",
            hint: `unable to convert ${sourceMtype} to ${target}`,
          });
        }

        assertActive(context);
        const outPath = join(dir, `variant-${index}`);
        await writeFile(outPath, data);
        variants.push(await upload(backend, outPath, target, data.length));

        await report(jobs, context, {
          stage: "fonts",
          counters: { fonts: { current: index + 1, total: targets.length } },
        });
      }

      return { variants };
    });
  };

  return {
    "media-info": mediaInfo,
    "media-thumbnail": mediaThumbnail,
    "profile-thumbnail": profileThumbnail,
    "font-variants": fontVariants,
  };
}
