import { afterEach, describe, expect, it, vi } from "vitest";
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import sharp from "sharp";
import type { BackendClient } from "../src/backend-client.js";
import type { JobsClient } from "../src/jobs-client.js";
import { ProcessingError } from "../src/errors.js";
import { makeMediaHandlers } from "../src/jobs/media.js";
import type { JobContext } from "../src/jobs-worker.js";

const FIXTURES = join(import.meta.dirname, "fixtures");

interface TestContext {
  context: JobContext;
  controller: AbortController;
}

function makeContext(overrides: Partial<JobContext> = {}): TestContext {
  const controller = new AbortController();
  return {
    controller,
    context: {
      jobId: "job-1",
      name: "media-info",
      queue: "media-fast",
      signal: controller.signal,
      abort: (reason) => controller.abort(reason),
      ...overrides,
    },
  };
}

const { context } = makeContext();

interface Uploaded {
  mtype: string;
  size: number;
}

function makeBackend(content: Buffer): {
  backend: BackendClient;
  uploads: Uploaded[];
  fetchInput: ReturnType<typeof vi.fn>;
} {
  const uploads: Uploaded[] = [];
  const fetchInput = vi.fn(async (_input: unknown, dest: string) => {
    await writeFile(dest, content);
  });
  const backend = {
    fetchInput,
    uploadResult: vi.fn(async (path: string, options: { mtype: string; filename: string }) => {
      const data = await readFile(path);
      uploads.push({ mtype: options.mtype, size: data.length });
      return { id: "object-1", uri: "/assets/by-id/object-1" };
    }),
  } as unknown as BackendClient;
  return { backend, uploads, fetchInput };
}

function makeJobs(): { jobs: JobsClient; progress: ReturnType<typeof vi.fn> } {
  const progress = vi.fn(async () => "run");
  const jobs = {
    progress,
    claim: vi.fn(),
    complete: vi.fn(),
    fail: vi.fn(),
  } as unknown as JobsClient;
  return { jobs, progress };
}

async function jpeg(width = 800, height = 600): Promise<Buffer> {
  return sharp({ create: { width, height, channels: 3, background: "#ffffff" } })
    .jpeg()
    .toBuffer();
}

const input = (size: number, mtype = "image/jpeg") => ({
  id: "11111111-1111-4111-8111-111111111111",
  mtype,
  size,
});

describe("media job handlers", () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it("answers the image info of a raster image", async () => {
    const content = await jpeg();
    const { backend, fetchInput } = makeBackend(content);
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const result = (await handlers["media-info"]({ input: input(content.length) }, context)) as Record<string, unknown>;

    expect(fetchInput).toHaveBeenCalledTimes(1);
    expect(result).toMatchObject({
      width: 800,
      height: 600,
      mtype: "image/jpeg",
      size: content.length,
    });
    expect(typeof result.orientation).toBe("number");
  });

  it("answers the thumbnail uploaded to tempfile", async () => {
    const content = await jpeg();
    const { backend, uploads } = makeBackend(content);
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const result = (await handlers["media-thumbnail"](
      {
        input: input(content.length),
        mediaId: "22222222-2222-4222-8222-222222222222",
        format: "jpeg",
        quality: 85,
        width: 100,
        height: 100,
      },
      context
    )) as { thumbnail: Record<string, unknown> };

    expect(result.thumbnail).toMatchObject({
      id: "object-1",
      uri: "/assets/by-id/object-1",
      mtype: "image/jpeg",
    });
    expect(result.thumbnail.size).toBe(uploads[0].size);
    expect(uploads[0].mtype).toBe("image/jpeg");
  });

  it("answers the profile photo as a jpeg", async () => {
    const content = await jpeg(30, 40);
    const { backend, uploads } = makeBackend(content);
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const result = (await handlers["profile-thumbnail"]({ input: input(content.length) }, context)) as {
      photo: Record<string, unknown>;
    };

    expect(result.photo).toMatchObject({ id: "object-1", mtype: "image/jpeg" });
    expect(uploads).toHaveLength(1);
  });

  it("generates the font variants and reports progress", async () => {
    const content = await readFile(join(FIXTURES, "font-1.ttf"));
    const { backend, uploads } = makeBackend(content);
    const { jobs, progress } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const result = (await handlers["font-variants"](
      {
        input: input(content.length, "font/ttf"),
        variantId: "33333333-3333-4333-8333-333333333333",
        sourceMtype: "font/ttf",
        targets: ["font/otf", "font/woff"],
      },
      { ...context, name: "font-variants", queue: "media" }
    )) as { variants: Array<Record<string, unknown>> };

    expect(result.variants).toHaveLength(2);
    expect(result.variants.map((variant) => variant.mtype)).toEqual(["font/otf", "font/woff"]);
    for (const variant of result.variants) {
      expect(variant).toMatchObject({ id: "object-1", uri: "/assets/by-id/object-1" });
    }
    expect(uploads.map((upload) => upload.mtype)).toEqual(["font/otf", "font/woff"]);

    expect(progress).toHaveBeenCalledTimes(2);
    expect(progress).toHaveBeenLastCalledWith("job-1", {
      stage: "fonts",
      counters: { fonts: { current: 2, total: 2 } },
    });
  });

  it("rejects invalid params before touching the input", async () => {
    const content = await jpeg();
    const { backend, fetchInput } = makeBackend(content);
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const error = await handlers["media-thumbnail"]({ input: input(content.length) }, context).catch((cause) => cause);

    expect(error).toBeInstanceOf(ProcessingError);
    expect(error.errorBody).toEqual({ type: "internal", code: "invalid-params", hint: "invalid job params" });
    expect(fetchInput).not.toHaveBeenCalled();
  });

  it("maps a processing failure to its domain error", async () => {
    const { backend } = makeBackend(Buffer.from("not an image"));
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });

    const error = await handlers["media-info"]({ input: input(12) }, context).catch((cause) => cause);

    expect(error).toBeInstanceOf(ProcessingError);
    expect(error.errorBody.code).toBe("invalid-image");
  });

  it("forwards the job signal to the image service", async () => {
    const content = await jpeg();
    const { backend } = makeBackend(content);
    const { jobs } = makeJobs();
    const handlers = makeMediaHandlers({ backend, jobs });
    const { context, controller } = makeContext();
    controller.abort("shutdown");

    const error = await handlers["media-info"]({ input: input(content.length) }, context).catch((cause) => cause);

    expect(error).toBeInstanceOf(Error);
    expect(error.message).toBe("Request cancelled");
  });

  it("aborts the job and stops the fan-out when the ledger says skip", async () => {
    const content = await readFile(join(FIXTURES, "font-1.ttf"));
    const { backend, uploads } = makeBackend(content);
    const { jobs, progress } = makeJobs();
    progress.mockResolvedValueOnce("skip");
    const handlers = makeMediaHandlers({ backend, jobs });
    const { context, controller } = makeContext();

    const error = await handlers["font-variants"](
      {
        input: input(content.length, "font/ttf"),
        variantId: "44444444-4444-4444-8444-444444444444",
        sourceMtype: "font/ttf",
        targets: ["font/otf", "font/woff"],
      },
      context
    ).catch((cause) => cause);

    expect(error).toBeInstanceOf(ProcessingError);
    expect(error.errorBody.code).toBe("job-not-active");
    expect(uploads).toHaveLength(1);
    expect(controller.signal.aborted).toBe(true);
    expect(controller.signal.reason).toBe("cancelled");
  });
});
