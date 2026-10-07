import { describe, it, expect, afterEach, vi } from "vitest";
import { EventEmitter } from "node:events";
import {
  configureSvgPool,
  shutdownSvgPool,
  runInSvgPool,
  setSvgWorkerFactory,
  type SvgWorker,
} from "../src/svg-pool.js";
import { ProcessingError } from "../src/middleware/error-handler.js";

class FakeWorker extends EventEmitter {
  public readonly posted: unknown[] = [];
  public terminated = false;

  public postMessage(value: unknown): void {
    this.posted.push(value);
  }

  public async terminate(): Promise<number> {
    this.terminated = true;
    this.emit("exit", 0);
    return 0;
  }

  public reply(value: unknown): void {
    this.emit("message", value);
  }

  public fail(err: Error): void {
    this.emit("error", err);
  }

  public exitUnexpectedly(code = 1): void {
    this.emit("exit", code);
  }
}

let created: FakeWorker[] = [];

function installFakeFactory(): void {
  created = [];
  setSvgWorkerFactory(() => {
    const worker = new FakeWorker();
    created.push(worker);
    return worker as unknown as SvgWorker;
  });
}

async function rejection(promise: Promise<unknown>): Promise<ProcessingError> {
  try {
    await promise;
  } catch (err) {
    return err as ProcessingError;
  }
  throw new Error("expected the promise to reject");
}

afterEach(async () => {
  await shutdownSvgPool();
  setSvgWorkerFactory(null);
});

describe("svg pool: dispatch", () => {
  it("runs jobs on idle workers and queues the rest", async () => {
    installFakeFactory();
    configureSvgPool({ workers: 2, maxOldGenerationSizeMb: 512, timeout: 1000 });

    const first = runInSvgPool("<svg>1</svg>");
    const second = runInSvgPool("<svg>2</svg>");
    expect(created).toHaveLength(2);
    expect(created[0].posted).toEqual(["<svg>1</svg>"]);
    expect(created[1].posted).toEqual(["<svg>2</svg>"]);

    const third = runInSvgPool("<svg>3</svg>");
    expect(created[0].posted).toHaveLength(1);

    created[0].reply({ ok: true, clean: "A" });
    await expect(first).resolves.toBe("A");
    expect(created[0].posted).toEqual(["<svg>1</svg>", "<svg>3</svg>"]);

    created[1].reply({ ok: true, clean: "B" });
    created[0].reply({ ok: true, clean: "C" });
    await expect(second).resolves.toBe("B");
    await expect(third).resolves.toBe("C");
  });

  it("rejects with the error body the worker reports", async () => {
    installFakeFactory();
    configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 1000 });

    const job = runInSvgPool("<svg/>");
    created[0].reply({
      ok: false,
      statusCode: 400,
      body: { type: "validation", code: "invalid-svg-file" },
    });

    const err = await rejection(job);
    expect(err).toBeInstanceOf(ProcessingError);
    expect(err.statusCode).toBe(400);
    expect(err.errorBody.code).toBe("invalid-svg-file");
  });
});

describe("svg pool: worker failure", () => {
  it("rejects the job and respawns when a worker errors", async () => {
    installFakeFactory();
    configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 1000 });

    const job = runInSvgPool("<svg/>");
    created[0].fail(new Error("boom"));

    const err = await rejection(job);
    expect(err.statusCode).toBe(503);
    expect(err.errorBody.code).toBe("svg-worker-failed");
    expect(created).toHaveLength(2);
  });

  it("rejects the job and respawns when a worker exits unexpectedly", async () => {
    installFakeFactory();
    configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 1000 });

    const job = runInSvgPool("<svg/>");
    created[0].exitUnexpectedly();

    const err = await rejection(job);
    expect(err.statusCode).toBe(503);
    expect(err.errorBody.code).toBe("svg-worker-exited");
    expect(created).toHaveLength(2);
  });
});

describe("svg pool: timeout", () => {
  it("terminates and respawns a worker that does not answer in time", async () => {
    vi.useFakeTimers();
    try {
      installFakeFactory();
      configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 1000 });

      const job = runInSvgPool("<svg/>");
      const settled = rejection(job);
      await vi.advanceTimersByTimeAsync(1000);

      const err = await settled;
      expect(err.statusCode).toBe(503);
      expect(err.errorBody.code).toBe("svg-timeout");
      expect(created[0].terminated).toBe(true);
      expect(created).toHaveLength(2);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("svg pool: shutdown", () => {
  it("rejects pending jobs and terminates the workers", async () => {
    installFakeFactory();
    configureSvgPool({ workers: 1, maxOldGenerationSizeMb: 512, timeout: 1000 });

    const job = runInSvgPool("<svg/>");
    const settled = rejection(job);
    await shutdownSvgPool();

    const err = await settled;
    expect(err.statusCode).toBe(503);
    expect(err.errorBody.code).toBe("svg-pool-stopped");
    expect(created[0].terminated).toBe(true);
  });
});
