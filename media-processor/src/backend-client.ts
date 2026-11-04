import { createWriteStream, openAsBlob } from "node:fs";
import { rm } from "node:fs/promises";
import { Readable, Transform, type TransformCallback } from "node:stream";
import { pipeline } from "node:stream/promises";
import type { InputRef } from "./protocol.js";
import { ProcessingError } from "./errors.js";
import { ERROR_SNIPPET_BYTES, isRecord, joinUri } from "./http.js";
import { createLogger } from "./logger.js";

export interface BackendClientOptions {
  backendUri: string;
  sharedKey: string;
  maxFileSize: number;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

export interface UploadedResult {
  id: string;
  uri: string;
}

const log = createLogger("backend-client");
const DEFAULT_TIMEOUT_MS = 120_000;

class PayloadTooLarge extends Error {}

class SizeGuard extends Transform {
  private written = 0;

  public constructor(private readonly maxBytes: number) {
    super();
  }

  public override _transform(chunk: Buffer, _encoding: BufferEncoding, callback: TransformCallback): void {
    this.written += chunk.length;
    if (this.written > this.maxBytes) {
      callback(new PayloadTooLarge());
      return;
    }
    callback(null, chunk);
  }
}

function isRedirect(status: number): boolean {
  return status === 301 || status === 302 || status === 303 || status === 307 || status === 308;
}

async function readSnippet(body: ReadableStream<Uint8Array> | null, limit: number): Promise<string> {
  if (!body) {
    return "";
  }
  const reader = body.getReader();
  const decoder = new TextDecoder();
  let text = "";
  try {
    while (text.length < limit) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      text += decoder.decode(value, { stream: true });
    }
  } finally {
    await reader.cancel().catch(() => {});
  }
  return text.slice(0, limit);
}

export class BackendClient {
  private readonly backendUri: string;
  private readonly sharedKey: string;
  private readonly maxFileSize: number;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;

  public constructor(options: BackendClientOptions) {
    this.backendUri = options.backendUri;
    this.sharedKey = options.sharedKey;
    this.maxFileSize = options.maxFileSize;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  private get headers(): Record<string, string> {
    return { "X-Shared-Key": `media-processor ${this.sharedKey}` };
  }

  private failure(code: string, hint: string): ProcessingError {
    return new ProcessingError(502, { type: "internal", code, hint });
  }

  private tooLarge(): ProcessingError {
    return new ProcessingError(413, {
      type: "restriction",
      code: "payload-too-large",
      hint: `input exceeds the maximum of ${this.maxFileSize} bytes`,
    });
  }

  /**
   * Download the input object to `destPath`. The body is streamed to disk with
   * a byte counter: a `Content-Length` over the cap fails before opening the
   * file, and a chunked body over the cap destroys the stream and removes the
   * partial file. The timeout covers the body read, not only the connection.
   */
  public async fetchInput(input: InputRef, destPath: string): Promise<void> {
    if (input.size > this.maxFileSize) {
      throw this.tooLarge();
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const url = joinUri(this.backendUri, `assets/by-id/${input.id}`);

    try {
      let response = await this.fetchImpl(url, {
        method: "GET",
        headers: this.headers,
        signal: controller.signal,
        redirect: "manual",
      });

      let redirects = 0;
      while (isRedirect(response.status)) {
        const location = response.headers.get("location");
        await response.body?.cancel().catch(() => {});
        if (!location) {
          throw this.failure("backend-fetch-failed", "backend answered a redirect without a location");
        }
        redirects += 1;
        if (redirects > 3) {
          throw this.failure("backend-fetch-failed", "too many redirects while downloading the input");
        }
        // The shared key is only for the backend. The object store must not
        // receive it in its access log.
        response = await this.fetchImpl(new URL(location, url).toString(), {
          method: "GET",
          signal: controller.signal,
          redirect: "manual",
        });
      }

      if (!response.ok) {
        const snippet = await readSnippet(response.body, ERROR_SNIPPET_BYTES);
        log.warn({ status: response.status, snippet }, "backend fetch failed");
        throw this.failure("backend-fetch-failed", "Failed to download the input");
      }

      const rawLength = response.headers.get("content-length");
      const length = rawLength ? Number(rawLength) : Number.NaN;
      if (Number.isFinite(length) && length > this.maxFileSize) {
        await response.body?.cancel().catch(() => {});
        throw this.tooLarge();
      }

      if (!response.body) {
        throw this.failure("backend-fetch-failed", "backend answered an empty body");
      }

      try {
        await pipeline(
          Readable.fromWeb(response.body as never),
          new SizeGuard(this.maxFileSize),
          createWriteStream(destPath)
        );
      } catch (cause) {
        await rm(destPath, { force: true }).catch(() => {});
        if (cause instanceof PayloadTooLarge) {
          throw this.tooLarge();
        }
        if (controller.signal.aborted) {
          throw this.failure("backend-fetch-failed", `timed out after ${this.timeoutMs}ms`);
        }
        log.warn({ cause }, "backend fetch failed");
        throw this.failure("backend-fetch-failed", "Failed to download the input");
      }
    } catch (cause) {
      if (cause instanceof ProcessingError) {
        throw cause;
      }
      if (controller.signal.aborted) {
        throw this.failure("backend-fetch-failed", `timed out after ${this.timeoutMs}ms`);
      }
      log.warn({ cause }, "backend fetch failed");
      throw this.failure("backend-fetch-failed", "Failed to download the input");
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * Upload a result file as a multipart field named `content`. The backend
   * stores it in the `tempfile` bucket and answers `{id, uri}`; both are
   * required: a partial answer means the storage object cannot be resolved
   * later.
   */
  public async uploadResult(path: string, options: { mtype: string; filename: string }): Promise<UploadedResult> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const url = joinUri(this.backendUri, "api/management/methods/upload-tempfile");

    try {
      const blob = await openAsBlob(path, { type: options.mtype });
      const form = new FormData();
      form.append("content", blob, options.filename);

      const response = await this.fetchImpl(url, {
        method: "POST",
        headers: this.headers,
        body: form,
        signal: controller.signal,
      });

      const text = await response.text();
      if (!response.ok) {
        log.warn({ status: response.status, body: text.slice(0, ERROR_SNIPPET_BYTES) }, "backend upload failed");
        throw this.failure("backend-upload-failed", "Failed to upload the result");
      }

      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        throw this.failure("backend-upload-failed", "backend answered a non-JSON body");
      }

      if (!isRecord(parsed) || typeof parsed.id !== "string" || typeof parsed.uri !== "string") {
        throw this.failure("backend-upload-failed", "backend answered without an object id and uri");
      }

      return { id: parsed.id, uri: parsed.uri };
    } catch (cause) {
      if (cause instanceof ProcessingError) {
        throw cause;
      }
      if (controller.signal.aborted) {
        throw this.failure("backend-upload-failed", `timed out after ${this.timeoutMs}ms`);
      }
      log.warn({ cause }, "backend upload failed");
      throw this.failure("backend-upload-failed", "Failed to upload the result");
    } finally {
      clearTimeout(timer);
    }
  }
}
