import { z } from "zod";

/**
 * Contract of the media jobs with the backend.
 *
 * The backend pushes `[job-id, scheduled-at]` as JSON to
 * `penpot.worker.queue:<tenant>:<queue>`; the worker claims the row with
 * `claim-job` before doing anything with it and settles it with
 * `complete-job` or `fail-job`. The row is the source of truth: there is no
 * request/reply hand-off on this path.
 *
 * The queue key and the payload mirror `app.jobs`; the job params mirror
 * `app.media.jobs`. The backend is the source of truth: if it changes, this
 * file changes.
 */

export const errorTypeSchema = z.enum(["validation", "restriction", "authorization", "internal"]);
export type ErrorType = z.infer<typeof errorTypeSchema>;

export interface WireError {
  type: ErrorType;
  code: string;
  hint?: string;
}

/**
 * Canonical vocabulary of the errors this worker can answer. It is
 * documentation as much as code: the backend maps `type` and `code` onto
 * its exceptions, and a code outside this list means a new call site, not
 * noise.
 */
export const ERROR_CODES = [
  "invalid-image",
  "unsupported-image-format",
  "invalid-font",
  "invalid-svg-file",
  "svg-too-large",
  "image-dimensions-exceeded",
  "image-pixel-count-exceeded",
  "output-dimensions-exceeded",
  "output-pixel-count-exceeded",
  "resource-limit-exceeded",
  "payload-too-large",
  "invalid-params",
  "backend-fetch-failed",
  "backend-upload-failed",
  "processing-error",
  "svg-sanitization-failed",
  "svg-timeout",
  "svg-worker-failed",
  "svg-worker-exited",
  "svg-pool-stopped",
  "svg-pool-degraded",
  "svg-pool-disabled",
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * A binary input the backend uploaded to `tempfile`. The job params never
 * carry a local path: the worker downloads the object with its shared key.
 */
export const inputRefSchema = z.object({
  id: z.string().regex(uuidRegex, "input id must be a uuid"),
  mtype: z.string().min(1),
  size: z.number().int().nonnegative(),
});

export type InputRef = z.infer<typeof inputRefSchema>;

/**
 * Queue key of the durable payload: `[job-id, scheduled-at]` as JSON. It
 * mirrors the backend dispatcher and the immediate push of `jobs/submit`;
 * the worker claims the row with `claim-job` before doing anything with it.
 */
export function jobsQueueKey(tenant: string, queue: string): string {
  return `penpot.worker.queue:${tenant}:${queue}`;
}

export const jobPayloadSchema = z.tuple([z.string().min(1), z.string().min(1)]);

export interface JobPayload {
  jobId: string;
  scheduledAt: string;
}

export function parseJobPayload(raw: string): JobPayload | null {
  try {
    const parsed = jobPayloadSchema.parse(JSON.parse(raw));
    return { jobId: parsed[0], scheduledAt: parsed[1] };
  } catch {
    return null;
  }
}

/* ---- job params (mirror app.media.jobs) ---- */

export const mediaInfoJobSchema = z
  .object({
    input: inputRefSchema,
  })
  .strict();

export const mediaThumbnailJobSchema = z
  .object({
    input: inputRefSchema,
    "media-id": z.string().regex(uuidRegex, "media-id must be a uuid"),
    format: z.enum(["jpeg", "webp", "png"]),
    quality: z.number().int().min(1).max(100),
    width: z.number().int().positive(),
    height: z.number().int().positive(),
  })
  .strict();

export const profileThumbnailJobSchema = z
  .object({
    input: inputRefSchema,
  })
  .strict();

export const fontVariantsJobSchema = z
  .object({
    input: inputRefSchema,
    "variant-id": z.string().regex(uuidRegex, "variant-id must be a uuid"),
    "source-mtype": z.string().min(1),
    targets: z.array(z.string().min(1)).min(1),
  })
  .strict();
