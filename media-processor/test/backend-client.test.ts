import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { readFile, rm, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { BackendClient } from "../src/backend-client.js";

const logger = vi.hoisted(() => ({
  warn: vi.fn(),
  info: vi.fn(),
  debug: vi.fn(),
  error: vi.fn(),
}));

vi.mock("../src/logger.js", () => ({
  createLogger: () => logger,
  logger,
}));

const input = {
  id: "11111111-1111-4111-8111-111111111111",
  mtype: "image/png",
  size: 5,
};

type Handler = (req: IncomingMessage, res: ServerResponse) => void | Promise<void>;

async function withServer(handler: Handler, fn: (uri: string) => Promise<void>): Promise<void> {
  const server = createServer((req, res) => {
    Promise.resolve(handler(req, res)).catch((cause) => res.destroy(cause instanceof Error ? cause : undefined));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  try {
    await fn(`http://127.0.0.1:${port}`);
  } finally {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

async function readBody(req: IncomingMessage): Promise<Buffer> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
}

function makeClient(
  uri: string,
  overrides: { maxFileSize?: number; timeoutMs?: number; fetchImpl?: typeof fetch } = {}
): BackendClient {
  return new BackendClient({
    backendUri: uri,
    sharedKey: "test-key",
    maxFileSize: 1024,
    timeoutMs: 1000,
    ...overrides,
  });
}

describe("BackendClient", () => {
  let dir: string;

  beforeEach(async () => {
    dir = await mkdtemp(join(tmpdir(), "penpot-media-client-"));
    logger.warn.mockClear();
  });

  afterEach(async () => {
    await rm(dir, { recursive: true, force: true });
  });

  describe("fetchInput", () => {
    it("downloads the input with the shared key", async () => {
      let seen: { method?: string; url?: string; key?: string } = {};
      await withServer(
        (req, res) => {
          seen = {
            method: req.method,
            url: req.url,
            key: req.headers["x-shared-key"] as string,
          };
          res.writeHead(200, { "content-length": "5" });
          res.end("hello");
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await makeClient(uri).fetchInput(input, dest);
          expect(await readFile(dest, "utf8")).toBe("hello");
        }
      );

      expect(seen).toEqual({
        method: "GET",
        url: `/assets/by-id/${input.id}`,
        key: "media-processor test-key",
      });
    });

    it("rejects a content-length over the cap before writing the file", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(200, { "content-length": "11" });
          res.end("hello world");
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await expect(makeClient(uri, { maxFileSize: 10 }).fetchInput(input, dest)).rejects.toMatchObject({
            errorBody: { code: "payload-too-large", type: "restriction" },
          });
          await expect(readFile(dest)).rejects.toThrow();
        }
      );
    });

    it("rejects a chunked body over the cap and removes the partial file", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(200);
          res.write("12345");
          res.write("678901");
          res.end();
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await expect(makeClient(uri, { maxFileSize: 10 }).fetchInput(input, dest)).rejects.toMatchObject({
            errorBody: { code: "payload-too-large" },
          });
          await expect(readFile(dest)).rejects.toThrow();
        }
      );
    });

    it("logs a bounded snippet of a failed download and keeps it out of the hint", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(500);
          res.end("x".repeat(100_000));
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          const error = await makeClient(uri)
            .fetchInput(input, dest)
            .catch((cause) => cause);
          expect(error).toBeInstanceOf(Error);
          expect(error.errorBody.code).toBe("backend-fetch-failed");
          expect(error.errorBody.hint).toBe("Failed to download the input");

          const [context] = logger.warn.mock.calls.at(-1)!;
          expect(context.status).toBe(500);
          expect(context.snippet.length).toBeLessThanOrEqual(4 * 1024);
        }
      );
    });

    it("does not leak a transport error in the download hint", async () => {
      const fetchImpl = vi.fn().mockRejectedValue(new Error("http://internal-host/secret"));
      const client = makeClient("http://127.0.0.1", { fetchImpl: fetchImpl as unknown as typeof fetch });
      const error = await client.fetchInput(input, join(dir, "input.bin")).catch((cause) => cause);

      expect(error.errorBody.code).toBe("backend-fetch-failed");
      expect(error.errorBody.hint).toBe("Failed to download the input");
      expect(logger.warn).toHaveBeenCalledWith({ cause: expect.any(Error) }, "backend fetch failed");
    });

    it("times out while reading the body", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(200, { "content-length": "100" });
          res.write("start");
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await expect(makeClient(uri, { timeoutMs: 50 }).fetchInput(input, dest)).rejects.toMatchObject({
            errorBody: { code: "backend-fetch-failed", hint: expect.stringMatching(/timed out/) },
          });
        }
      );
    });

    it("follows a redirect without forwarding the shared key", async () => {
      let redirectedKey: string | undefined;
      await withServer(
        (req, res) => {
          if (req.url === `/assets/by-id/${input.id}`) {
            res.writeHead(307, { location: "/files/object" });
            res.end();
          } else {
            redirectedKey = req.headers["x-shared-key"] as string | undefined;
            res.writeHead(200, { "content-length": "5" });
            res.end("hello");
          }
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await makeClient(uri).fetchInput(input, dest);
          expect(await readFile(dest, "utf8")).toBe("hello");
        }
      );
      expect(redirectedKey).toBeUndefined();
    });

    it("rejects a redirect without a location", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(307);
          res.end();
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          await expect(makeClient(uri).fetchInput(input, dest)).rejects.toMatchObject({
            errorBody: { code: "backend-fetch-failed" },
          });
          await expect(readFile(dest)).rejects.toThrow();
        }
      );
    });

    it("rejects more than three redirects", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(307, { location: "/loop" });
          res.end();
        },
        async (uri) => {
          const dest = join(dir, "input.bin");
          const error = await makeClient(uri)
            .fetchInput(input, dest)
            .catch((cause) => cause);
          expect(error.errorBody.code).toBe("backend-fetch-failed");
          expect(error.errorBody.hint).toMatch(/too many redirects/);
        }
      );
    });
  });

  describe("uploadResult", () => {
    it("uploads the result as multipart and returns the object", async () => {
      let seen: { contentType?: string; key?: string; body?: string } = {};
      await withServer(
        async (req, res) => {
          seen = {
            contentType: req.headers["content-type"] as string,
            key: req.headers["x-shared-key"] as string,
            body: (await readBody(req)).toString("utf8"),
          };
          res.writeHead(200, { "content-type": "application/json" });
          res.end(JSON.stringify({ id: "object-1", uri: "/assets/by-id/object-1" }));
        },
        async (uri) => {
          const path = join(dir, "thumb.png");
          await writeFile(path, "PNGDATA");
          const result = await makeClient(uri).uploadResult(path, {
            mtype: "image/png",
            filename: "thumb.png",
          });
          expect(result).toEqual({ id: "object-1", uri: "/assets/by-id/object-1" });
        }
      );

      expect(seen.contentType).toMatch(/^multipart\/form-data; boundary=/);
      expect(seen.key).toBe("media-processor test-key");
      expect(seen.body).toContain('name="content"');
      expect(seen.body).toContain('filename="thumb.png"');
      expect(seen.body).toContain("PNGDATA");
    });

    it.each([
      ["an empty object", "{}"],
      ["a partial object", '{"id":"object-1"}'],
      ["a non-JSON body", "not-json"],
    ])("rejects %s as a failed upload", async (_label, body) => {
      await withServer(
        (_req, res) => {
          res.writeHead(200, { "content-type": "application/json" });
          res.end(body);
        },
        async (uri) => {
          const path = join(dir, "thumb.png");
          await writeFile(path, "PNGDATA");
          await expect(
            makeClient(uri).uploadResult(path, { mtype: "image/png", filename: "thumb.png" })
          ).rejects.toMatchObject({ errorBody: { code: "backend-upload-failed" } });
        }
      );
    });

    it("logs the body of a failed upload and keeps it out of the hint", async () => {
      const payload = "s".repeat(1024 * 1024);
      await withServer(
        (_req, res) => {
          res.writeHead(500);
          res.end(payload);
        },
        async (uri) => {
          const path = join(dir, "thumb.png");
          await writeFile(path, "PNGDATA");
          const error = await makeClient(uri)
            .uploadResult(path, { mtype: "image/png", filename: "thumb.png" })
            .catch((cause) => cause);

          expect(error.errorBody.code).toBe("backend-upload-failed");
          expect(error.errorBody.hint).toBe("Failed to upload the result");

          const [context] = logger.warn.mock.calls.at(-1)!;
          expect(context.status).toBe(500);
          expect(context.body).toBe(payload.slice(0, 4 * 1024));
        }
      );
    });

    it("rejects a failed upload", async () => {
      await withServer(
        (_req, res) => {
          res.writeHead(500);
          res.end("boom");
        },
        async (uri) => {
          const path = join(dir, "thumb.png");
          await writeFile(path, "PNGDATA");
          await expect(
            makeClient(uri).uploadResult(path, { mtype: "image/png", filename: "thumb.png" })
          ).rejects.toMatchObject({ errorBody: { code: "backend-upload-failed" } });
        }
      );
    });

    it("does not leak a transport error in the upload hint", async () => {
      const fetchImpl = vi.fn().mockRejectedValue(new Error("http://internal-host/secret"));
      const client = makeClient("http://127.0.0.1", { fetchImpl: fetchImpl as unknown as typeof fetch });
      const path = join(dir, "thumb.png");
      await writeFile(path, "PNGDATA");

      const error = await client
        .uploadResult(path, { mtype: "image/png", filename: "thumb.png" })
        .catch((cause) => cause);

      expect(error.errorBody.code).toBe("backend-upload-failed");
      expect(error.errorBody.hint).toBe("Failed to upload the result");
      expect(logger.warn).toHaveBeenCalledWith({ cause: expect.any(Error) }, "backend upload failed");
    });

    it("times out the upload", async () => {
      await withServer(
        async (req, _res) => {
          await readBody(req);
        },
        async (uri) => {
          const path = join(dir, "thumb.png");
          await writeFile(path, "PNGDATA");
          await expect(
            makeClient(uri, { timeoutMs: 50 }).uploadResult(path, {
              mtype: "image/png",
              filename: "thumb.png",
            })
          ).rejects.toMatchObject({
            errorBody: { code: "backend-upload-failed", hint: expect.stringMatching(/timed out/) },
          });
        }
      );
    });
  });
});
