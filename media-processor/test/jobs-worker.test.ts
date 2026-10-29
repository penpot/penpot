import { afterEach, describe, expect, it, vi } from "vitest";
import type { JobsClient } from "../src/jobs-client.js";
import { JobsWorker, type JobHandler } from "../src/jobs-worker.js";
import { ProcessingError } from "../src/errors.js";
import { jobsQueueKey } from "../src/protocol.js";
import { FakeRedisServer } from "./fake-redis.js";

const tenant = "default";
const FAST = "media-fast";
const SLOW = "media";

function payload(jobId: string): string {
  return JSON.stringify([jobId, "2026-11-03T10:00:00.000Z"]);
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(check: () => boolean, timeoutMs = 2000): Promise<void> {
  const started = Date.now();
  while (!check()) {
    if (Date.now() - started > timeoutMs) {
      throw new Error("timed out waiting for condition");
    }
    await delay(1);
  }
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

interface FakeClient {
  claim: ReturnType<typeof vi.fn>;
  progress: ReturnType<typeof vi.fn>;
  complete: ReturnType<typeof vi.fn>;
  fail: ReturnType<typeof vi.fn>;
}

function makeClient(overrides: Partial<FakeClient> = {}): FakeClient {
  return {
    claim: vi.fn(async (jobId: string) => ({ action: "run", name: nameOf(jobId), params: { jobId } })),
    progress: vi.fn(async () => "run"),
    complete: vi.fn(async () => "run"),
    fail: vi.fn(async () => "run"),
    ...overrides,
  };
}

function nameOf(jobId: string): string {
  return jobId.startsWith("slow") ? "font-variants" : "media-info";
}

async function startWorker(
  server: FakeRedisServer,
  client: FakeClient,
  handlers: Record<string, JobHandler>,
  overrides: { fastConcurrency?: number; slowConcurrency?: number; stopTimeoutMs?: number } = {}
): Promise<JobsWorker> {
  const worker = new JobsWorker({
    tenant,
    client: client as unknown as JobsClient,
    handlers,
    fastQueue: FAST,
    slowQueue: SLOW,
    fastConcurrency: 1,
    slowConcurrency: 1,
    pollTimeoutSeconds: 0.02,
    reconnectDelayMs: 5,
    stopTimeoutMs: 500,
    createConnection: () => server.connect(),
    ...overrides,
  });
  await worker.start();
  return worker;
}

describe("JobsWorker", () => {
  let workers: JobsWorker[] = [];

  afterEach(async () => {
    await Promise.allSettled(workers.map((worker) => worker.stop()));
    workers = [];
  });

  it("runs the handler of a claimed job and completes it", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handler = vi.fn<JobHandler>(async () => ({ width: 10 }));

    workers.push(await startWorker(server, client, { "media-info": handler }));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));

    await waitFor(() => client.complete.mock.calls.length === 1);

    expect(client.claim).toHaveBeenCalledWith("fast-1", "2026-11-03T10:00:00.000Z");
    expect(handler).toHaveBeenCalledWith(
      { jobId: "fast-1" },
      expect.objectContaining({
        jobId: "fast-1",
        name: "media-info",
        queue: FAST,
        signal: expect.any(AbortSignal),
      })
    );
    expect(client.complete).toHaveBeenCalledWith("fast-1", { width: 10 });
    expect(client.fail).not.toHaveBeenCalled();
  });

  it("does nothing when the claim answers skip", async () => {
    const server = new FakeRedisServer();
    const client = makeClient({ claim: vi.fn(async () => ({ action: "skip" })) });
    const handler = vi.fn<JobHandler>(async () => ({}));

    workers.push(await startWorker(server, client, { "media-info": handler }));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));

    await waitFor(() => client.claim.mock.calls.length === 1);
    await delay(20);

    expect(handler).not.toHaveBeenCalled();
    expect(client.complete).not.toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();
  });

  it("drops a corrupt payload and keeps polling", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handler = vi.fn<JobHandler>(async () => ({}));

    workers.push(await startWorker(server, client, { "media-info": handler }));
    server.push(jobsQueueKey(tenant, FAST), "{not json}");
    server.push(jobsQueueKey(tenant, FAST), payload("fast-2"));

    await waitFor(() => client.complete.mock.calls.length === 1);
    expect(handler).toHaveBeenCalledTimes(1);
    expect(client.claim).toHaveBeenCalledTimes(1);
  });

  it("fails a job with an unknown name without retrying it", async () => {
    const server = new FakeRedisServer();
    const client = makeClient({ claim: vi.fn(async () => ({ action: "run", name: "unknown-job", params: {} })) });

    workers.push(await startWorker(server, client, { "media-info": vi.fn<JobHandler>(async () => ({})) }));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));

    await waitFor(() => client.fail.mock.calls.length === 1);

    const [jobId, error, retriable] = client.fail.mock.calls[0];
    expect(jobId).toBe("fast-1");
    expect(error).toMatchObject({ type: "internal", code: "no-job-handler" });
    expect(retriable).toBe(false);
    expect(client.complete).not.toHaveBeenCalled();
  });

  it("fails a domain error without retrying and a crash as retriable", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handlers: Record<string, JobHandler> = {
      "media-info": async () => {
        throw new ProcessingError(413, {
          type: "restriction",
          code: "payload-too-large",
          hint: "input exceeds the maximum",
        });
      },
      "font-variants": async () => {
        throw new Error("boom");
      },
    };

    workers.push(await startWorker(server, client, handlers));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));
    server.push(jobsQueueKey(tenant, SLOW), payload("slow-1"));

    await waitFor(() => client.fail.mock.calls.length === 2);

    const domain = client.fail.mock.calls.find(([jobId]) => jobId === "fast-1")!;
    expect(domain[1]).toMatchObject({ type: "restriction", code: "payload-too-large" });
    expect(domain[2]).toBe(false);

    const crash = client.fail.mock.calls.find(([jobId]) => jobId === "slow-1")!;
    expect(crash[1]).toMatchObject({ type: "internal", code: "processing-error" });
    expect(crash[2]).toBe(true);
  });

  it("does not block the fast queue behind a slow job", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const slow = deferred<unknown>();
    const handlers: Record<string, JobHandler> = {
      "media-info": vi.fn<JobHandler>(async () => ({ fast: true })),
      "font-variants": vi.fn<JobHandler>(async () => slow.promise),
    };

    workers.push(await startWorker(server, client, handlers));
    server.push(jobsQueueKey(tenant, SLOW), payload("slow-1"));
    await waitFor(() => client.claim.mock.calls.some(([jobId]) => jobId === "slow-1"));

    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));
    await waitFor(() => client.complete.mock.calls.some(([jobId]) => jobId === "fast-1"));

    expect(client.complete).not.toHaveBeenCalledWith("slow-1", expect.anything());
    slow.resolve({});
    await waitFor(() => client.complete.mock.calls.some(([jobId]) => jobId === "slow-1"));
  });

  it("aborts the job in flight when stopping and reports it retriable", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handler = vi.fn<JobHandler>(async (_params, context) => {
      await new Promise((_resolve, reject) => {
        context.signal.addEventListener("abort", () => reject(new Error("aborted")), { once: true });
      });
      return { done: true };
    });

    const worker = await startWorker(server, client, { "media-info": handler });
    workers.push(worker);
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));
    await waitFor(() => handler.mock.calls.length === 1);

    await worker.stop();

    const [_params, context] = handler.mock.calls[0];
    expect(context.signal.aborted).toBe(true);
    expect(context.signal.reason).toBe("shutdown");
    expect(client.complete).not.toHaveBeenCalled();
    expect(client.fail).toHaveBeenCalledTimes(1);
    expect(client.fail.mock.calls[0][1]).toMatchObject({ type: "internal", code: "worker-shutdown" });
    expect(client.fail.mock.calls[0][2]).toBe(true);
  });

  it("drops the outcome of a job the ledger aborted", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handler = vi.fn<JobHandler>(async (_params, context) => {
      context.abort("cancelled");
      return { ignored: true };
    });

    workers.push(await startWorker(server, client, { "media-info": handler }));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));

    await waitFor(() => handler.mock.calls.length === 1);
    await delay(20);

    expect(client.complete).not.toHaveBeenCalled();
    expect(client.fail).not.toHaveBeenCalled();

    // The poller keeps working after the aborted job.
    server.push(jobsQueueKey(tenant, FAST), payload("fast-2"));
    await waitFor(() => handler.mock.calls.length === 2);
  });

  it("reports a job that outlives the shutdown as retriable", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const running = deferred<unknown>();
    const handler = vi.fn<JobHandler>(async () => running.promise);

    const worker = await startWorker(server, client, { "media-info": handler }, { stopTimeoutMs: 20 });
    workers.push(worker);
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));
    await waitFor(() => handler.mock.calls.length === 1);

    await worker.stop();

    expect(client.fail).toHaveBeenCalledTimes(1);
    const [jobId, error, retriable] = client.fail.mock.calls[0];
    expect(jobId).toBe("fast-1");
    expect(error).toMatchObject({ type: "internal", code: "worker-shutdown" });
    expect(retriable).toBe(true);

    // The row already belongs to another attempt: the handler must not settle
    // it again when it finally ends.
    running.resolve({ done: true });
    await delay(10);
    expect(client.complete).not.toHaveBeenCalled();
    expect(client.fail).toHaveBeenCalledTimes(1);
  });

  it("never writes the reply key", async () => {
    const server = new FakeRedisServer();
    const client = makeClient();
    const handler = vi.fn<JobHandler>(async () => ({ ok: true }));

    workers.push(await startWorker(server, client, { "media-info": handler }));
    server.push(jobsQueueKey(tenant, FAST), payload("fast-1"));

    await waitFor(() => client.complete.mock.calls.length === 1);
    expect(await server.take(`penpot.worker.reply:${tenant}:fast-1`, 0.1)).toBeNull();
  });
});
