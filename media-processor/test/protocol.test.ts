import { describe, expect, it } from "vitest";
import {
  ERROR_CODES,
  fontVariantsJobSchema,
  inputRefSchema,
  jobsQueueKey,
  mediaInfoJobSchema,
  mediaThumbnailJobSchema,
  parseJobPayload,
  profileThumbnailJobSchema,
} from "../src/protocol.js";

const input = {
  id: "11111111-1111-4111-8111-111111111111",
  mtype: "image/png",
  size: 1024,
};

const scheduledAt = "2026-11-03T10:00:00.000Z";

describe("job payload", () => {
  it("builds the queue key of a tenant and a queue", () => {
    expect(jobsQueueKey("default", "media-fast")).toBe("penpot.worker.queue:default:media-fast");
  });

  it("parses the durable two-element payload", () => {
    expect(parseJobPayload(JSON.stringify(["job-1", scheduledAt]))).toEqual({
      jobId: "job-1",
      scheduledAt,
    });
  });

  it("rejects the retired ephemeral payload", () => {
    expect(parseJobPayload(JSON.stringify(["req-1", "reply-1", "image-info", {}]))).toBeNull();
  });

  it("rejects a payload with more than two elements", () => {
    expect(parseJobPayload(JSON.stringify(["job-1", scheduledAt, "extra"]))).toBeNull();
  });

  it("rejects invalid JSON and empty fields", () => {
    expect(parseJobPayload("not-json")).toBeNull();
    expect(parseJobPayload(JSON.stringify(["", scheduledAt]))).toBeNull();
    expect(parseJobPayload(JSON.stringify(["job-1", ""]))).toBeNull();
  });
});

describe("job params", () => {
  it("accepts the media-info params", () => {
    expect(mediaInfoJobSchema.parse({ input })).toEqual({ input });
  });

  it("accepts the thumbnail params with their kebab-case keys", () => {
    const params = {
      input,
      "media-id": "22222222-2222-4222-8222-222222222222",
      format: "webp",
      quality: 85,
      width: 100,
      height: 100,
    };
    expect(mediaThumbnailJobSchema.parse(params)).toEqual(params);
    expect(() => mediaThumbnailJobSchema.parse({ ...params, format: "gif" })).toThrow();
    expect(() => mediaThumbnailJobSchema.parse({ ...params, quality: 101 })).toThrow();
  });

  it("rejects a thumbnail without media-id or with extra keys", () => {
    const params = { input, format: "jpeg", quality: 85, width: 10, height: 10 };
    expect(() => mediaThumbnailJobSchema.parse(params)).toThrow();
    expect(() =>
      mediaThumbnailJobSchema.parse({
        ...params,
        "media-id": "22222222-2222-4222-8222-222222222222",
        extra: true,
      })
    ).toThrow();
  });

  it("accepts the profile-thumbnail params", () => {
    expect(profileThumbnailJobSchema.parse({ input })).toEqual({ input });
    expect(() => profileThumbnailJobSchema.parse({ input, "profile-id": "x" })).toThrow();
  });

  it("accepts the font-variants params and rejects an empty target list", () => {
    const params = {
      input,
      "variant-id": "33333333-3333-4333-8333-333333333333",
      "source-mtype": "font/ttf",
      targets: ["font/otf", "font/woff"],
    };
    expect(fontVariantsJobSchema.parse(params)).toEqual(params);
    expect(() => fontVariantsJobSchema.parse({ ...params, targets: [] })).toThrow();
  });

  it("rejects a profile-thumbnail input that is not a photo", () => {
    expect(profileThumbnailJobSchema.parse({ input: { ...input, mtype: "image/webp" } })).toBeTruthy();
    expect(() => profileThumbnailJobSchema.parse({ input: { ...input, mtype: "image/svg+xml" } })).toThrow();
  });

  it("rejects a font target that is never generated", () => {
    const params = {
      input,
      "variant-id": "33333333-3333-4333-8333-333333333333",
      "source-mtype": "font/ttf",
      targets: ["font/otf"],
    };
    expect(fontVariantsJobSchema.parse(params)).toEqual(params);
    expect(() => fontVariantsJobSchema.parse({ ...params, targets: ["font/woff2"] })).toThrow();
    expect(() => fontVariantsJobSchema.parse({ ...params, targets: ["application/zip"] })).toThrow();
  });
});

describe("input ref", () => {
  it("accepts a uuid with non-negative size", () => {
    expect(inputRefSchema.parse(input)).toEqual(input);
  });

  it("rejects a non-uuid id and a negative size", () => {
    expect(() => inputRefSchema.parse({ ...input, id: "nope" })).toThrow();
    expect(() => inputRefSchema.parse({ ...input, size: -1 })).toThrow();
  });
});

describe("error vocabulary", () => {
  it("contains the codes the worker emits", () => {
    for (const code of [
      "invalid-image",
      "invalid-font",
      "payload-too-large",
      "backend-fetch-failed",
      "backend-upload-failed",
      "processing-error",
      "settle-failed",
    ]) {
      expect(ERROR_CODES).toContain(code);
    }
  });
});
