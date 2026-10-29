import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { JobsClient, JobsClientError } from "../src/jobs-client.js";

type Handler = (req: IncomingMessage, res: ServerResponse) => void | Promise<void>;

interface Seen {
  method?: string;
  url?: string;
  key?: string;
  contentType?: string;
  body?: Record<string, unknown>;
}

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

async function readBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(Buffer.from(chunk));
  }
  const text = Buffer.concat(chunks).toString("utf8");
  return text.length > 0 ? JSON.parse(text) : {};
}

/** Answer every request with `body` and record what arrived. */
function responder(body: Record<string, unknown>, seen: Seen): Handler {
  return async (req, res) => {
    seen.method = req.method;
    seen.url = req.url;
    seen.key = req.headers["x-shared-key"] as string;
    seen.contentType = req.headers["content-type"] as string;
    seen.body = await readBody(req);
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(body));
  };
}

function makeClient(uri: string, overrides: { timeoutMs?: number; fetchImpl?: typeof fetch } = {}): JobsClient {
  return new JobsClient({
    backendUri: uri,
    sharedKey: "test-key",
    timeoutMs: 1000,
    ...overrides,
  });
}

describe("JobsClient", () => {
  let seen: Seen;

  beforeEach(() => {
    seen = {};
  });

  afterEach(() => {
    seen = {};
  });

  it("claims a runnable job with the shared key", async () => {
    await withServer(
      responder({ action: "run", name: "media-info", params: { input: { id: "x" } } }, seen),
      async (uri) => {
        const job = await makeClient(uri).claim("job-1", "2026-11-03T10:00:00.000Z");
        expect(job).toEqual({ action: "run", name: "media-info", params: { input: { id: "x" } } });
      }
    );

    expect(seen.method).toBe("POST");
    expect(seen.url).toBe("/api/management/methods/claim-job");
    expect(seen.key).toBe("media-processor test-key");
    expect(seen.contentType).toBe("application/json");
    expect(seen.body).toEqual({ "job-id": "job-1", "scheduled-at": "2026-11-03T10:00:00.000Z" });
  });

  it("answers a skip when the claim is no longer valid", async () => {
    await withServer(responder({ action: "skip" }, seen), async (uri) => {
      await expect(makeClient(uri).claim("job-1", "2026-11-03T10:00:00.000Z")).resolves.toEqual({ action: "skip" });
    });
  });

  it("rejects a run without a job name", async () => {
    await withServer(responder({ action: "run" }, seen), async (uri) => {
      await expect(makeClient(uri).claim("job-1", "2026-11-03T10:00:00.000Z")).rejects.toBeInstanceOf(JobsClientError);
    });
  });

  it("reports progress and understands run and skip", async () => {
    const progress = { stage: "pages", counters: { pages: { current: 3, total: 10 } } };

    await withServer(responder({ action: "run" }, seen), async (uri) => {
      await expect(makeClient(uri).progress("job-1", progress)).resolves.toBe("run");
    });
    expect(seen.url).toBe("/api/management/methods/report-job-progress");
    expect(seen.body).toEqual({ "job-id": "job-1", progress });

    seen = {};
    await withServer(responder({ action: "skip" }, seen), async (uri) => {
      await expect(makeClient(uri).progress("job-1", progress)).resolves.toBe("skip");
    });
  });

  it("completes a job with its result", async () => {
    const result = { thumbnail: { id: "x", uri: "/assets/by-id/x", mtype: "image/jpeg", size: 10 } };

    await withServer(responder({ action: "run" }, seen), async (uri) => {
      await expect(makeClient(uri).complete("job-1", result)).resolves.toBe("run");
    });
    expect(seen.url).toBe("/api/management/methods/complete-job");
    expect(seen.body).toEqual({ "job-id": "job-1", result });
  });

  it("sends an optional resource id when completing", async () => {
    await withServer(responder({ action: "run" }, seen), async (uri) => {
      await makeClient(uri).complete("job-1", { v: 1 }, "resource-1");
    });
    expect(seen.body).toEqual({ "job-id": "job-1", result: { v: 1 }, "resource-id": "resource-1" });
  });

  it("fails a job with retriable and understands retry", async () => {
    const error = { type: "internal", code: "worker-shutdown", hint: "going away" };

    await withServer(responder({ action: "retry" }, seen), async (uri) => {
      await expect(makeClient(uri).fail("job-1", error, true)).resolves.toBe("retry");
    });
    expect(seen.url).toBe("/api/management/methods/fail-job");
    expect(seen.body).toEqual({ "job-id": "job-1", error, "retriable?": true });

    seen = {};
    await withServer(responder({ action: "run" }, seen), async (uri) => {
      await expect(makeClient(uri).fail("job-1", error)).resolves.toBe("run");
    });
    expect(seen.body).toEqual({ "job-id": "job-1", error, "retriable?": false });
  });

  it("never confuses an HTTP error with a skip", async () => {
    const reject = (status: number): Handler => {
      return (_req, res) => {
        res.writeHead(status);
        res.end("not allowed");
      };
    };

    await withServer(reject(403), async (uri) => {
      const error = await makeClient(uri)
        .claim("job-1", "2026-11-03T10:00:00.000Z")
        .catch((cause) => cause);
      expect(error).toBeInstanceOf(JobsClientError);
      expect(error.status).toBe(403);
    });

    await withServer(reject(500), async (uri) => {
      const error = await makeClient(uri)
        .progress("job-1", { stage: "x" })
        .catch((cause) => cause);
      expect(error).toBeInstanceOf(JobsClientError);
      expect(error.status).toBe(500);
    });
  });

  it("rejects a non-JSON answer", async () => {
    await withServer(
      (_req, res) => {
        res.writeHead(200, { "content-type": "application/json" });
        res.end("not-json");
      },
      async (uri) => {
        await expect(makeClient(uri).progress("job-1", { stage: "x" })).rejects.toBeInstanceOf(JobsClientError);
      }
    );
  });

  it("rejects an action outside the contract", async () => {
    await withServer(responder({ action: "later" }, seen), async (uri) => {
      await expect(makeClient(uri).progress("job-1", { stage: "x" })).rejects.toBeInstanceOf(JobsClientError);
    });
  });

  it("times out a request that never answers", async () => {
    await withServer(
      () => {
        // never respond
      },
      async (uri) => {
        const error = await makeClient(uri, { timeoutMs: 50 })
          .claim("job-1", "2026-11-03T10:00:00.000Z")
          .catch((cause) => cause);
        expect(error).toBeInstanceOf(JobsClientError);
        expect(error.status).toBe(0);
      }
    );
  });
});
