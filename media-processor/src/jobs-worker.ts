import { ProcessingError } from "./errors.js";
import { createLogger } from "./logger.js";
import { jobsQueueKey, parseJobPayload } from "./protocol.js";
import type { JobError, JobsClient } from "./jobs-client.js";

/**
 * Durable worker of the media jobs.
 *
 * Two groups of pollers consume the queues `app.media.jobs/queue-pair`
 * routes to: a fast group for the synchronous jobs (`media-info`,
 * `profile-thumbnail`) and a slow group for the asynchronous ones
 * (`media-thumbnail`, `font-variants`), so a fan-out cannot delay a upload.
 *
 * Each payload is `[job-id, scheduled-at]`. The poller claims the row, runs
 * the handler registered for the job name and settles the row with
 * `complete-job` or `fail-job`. The worker never publishes anything on the
 * reply key: the backend does it in the terminal transaction.
 *
 * Every claimed job gets its own `AbortController`. It is aborted when the
 * ledger says the job is no longer active (`report-job-progress` answers
 * `skip`) and when the worker stops; the handler forwards the signal to the
 * image and font services, so a font child process dies with it. A handler
 * that ends aborted never uploads or settles the row.
 */

/** The subset of Redis the pollers need. */
export interface RedisLike {
  blpop(key: string, timeout: number): Promise<[string, string] | null>;
  quit(): Promise<unknown>;
}

/**
 * Why a job was aborted. `cancelled` comes from the ledger (the row is
 * already terminal); `shutdown` comes from the worker stopping, and the row
 * is reported as a retriable failure.
 */
export type JobAbortReason = "cancelled" | "shutdown";

export interface JobContext {
  jobId: string;
  name: string;
  queue: string;
  /** Aborted when the job is no longer active or the worker stops. */
  signal: AbortSignal;
  /** Abort this job's signal with the reason the worker will inspect. */
  abort(reason: JobAbortReason): void;
}

export type JobHandler = (params: unknown, context: JobContext) => Promise<unknown>;

export interface JobsWorkerOptions {
  tenant: string;
  client: JobsClient;
  handlers: Record<string, JobHandler>;
  fastQueue: string;
  slowQueue: string;
  fastConcurrency?: number;
  slowConcurrency?: number;
  createConnection: () => RedisLike;
  pollTimeoutSeconds?: number;
  reconnectDelayMs?: number;
  stopTimeoutMs?: number;
}

interface PollerGroup {
  name: string;
  queue: string;
  concurrency: number;
}

const DEFAULT_POLL_TIMEOUT_SECONDS = 5;
const DEFAULT_RECONNECT_DELAY_MS = 1000;
const DEFAULT_STOP_TIMEOUT_MS = 30_000;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | undefined> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => resolve(undefined), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (cause) => {
        clearTimeout(timer);
        reject(cause);
      }
    );
  });
}

export class JobsWorker {
  private readonly log = createLogger("jobs-worker");
  private readonly tenant: string;
  private readonly client: JobsClient;
  private readonly handlers: Record<string, JobHandler>;
  private readonly groups: PollerGroup[];
  private readonly createConnection: () => RedisLike;
  private readonly pollTimeoutSeconds: number;
  private readonly reconnectDelayMs: number;
  private readonly stopTimeoutMs: number;

  private running = false;
  private conns: RedisLike[] = [];
  private pollers: Promise<void>[] = [];
  private lastRedisError = 0;
  /** Jobs handed to a handler and not settled yet, with their own controller. */
  private readonly inflight = new Map<string, AbortController>();
  /** Jobs the shutdown already settled: their handler must not settle them again. */
  private readonly abandoned = new Set<string>();

  public constructor(options: JobsWorkerOptions) {
    this.tenant = options.tenant;
    this.client = options.client;
    this.handlers = options.handlers;
    this.createConnection = options.createConnection;
    this.pollTimeoutSeconds = options.pollTimeoutSeconds ?? DEFAULT_POLL_TIMEOUT_SECONDS;
    this.reconnectDelayMs = options.reconnectDelayMs ?? DEFAULT_RECONNECT_DELAY_MS;
    this.stopTimeoutMs = options.stopTimeoutMs ?? DEFAULT_STOP_TIMEOUT_MS;
    this.groups = [
      {
        name: "fast",
        queue: options.fastQueue,
        concurrency: Math.max(1, options.fastConcurrency ?? 2),
      },
      {
        name: "slow",
        queue: options.slowQueue,
        concurrency: Math.max(1, options.slowConcurrency ?? 2),
      },
    ];
  }

  /**
   * Start both groups. Each poller owns its connection: the blocking
   * `BLPOP` holds it for the wait and the poller awaits its handler before
   * popping again, so the connections are the concurrency ceiling.
   */
  public async start(): Promise<void> {
    if (this.running) {
      return;
    }
    this.running = true;

    const pollers: Promise<void>[] = [];
    for (const group of this.groups) {
      const conns = Array.from({ length: group.concurrency }, () => this.createConnection());
      this.conns.push(...conns);
      pollers.push(...conns.map((conn) => this.pollLoop(conn, group)));
      this.log.info({ queue: group.queue, pollers: group.concurrency }, "jobs poller started");
    }
    this.pollers = pollers;
  }

  /**
   * Abort the jobs in flight, let the pollers settle (bounded), then close
   * the connections. A handler that honours the signal ends at once and
   * reports its row as a retriable failure; one that ignores it is reported
   * after the cap. Either way the substrate schedules another attempt instead
   * of waiting for the lease. Idempotent.
   */
  public async stop(): Promise<void> {
    if (!this.running) {
      return;
    }
    this.running = false;

    for (const [jobId, controller] of this.inflight) {
      this.log.info({ jobId }, "aborting job in flight");
      controller.abort("shutdown" satisfies JobAbortReason);
    }

    const settled = await withTimeout(Promise.allSettled(this.pollers), this.stopTimeoutMs);
    if (settled === undefined) {
      // A handler ignored the abort. Report it now: it keeps running but its
      // result will be dropped, because the row already belongs to another
      // attempt.
      await Promise.allSettled([...this.inflight.keys()].map((jobId) => this.reportShutdown(jobId)));
    }
    this.pollers = [];

    await Promise.allSettled(this.conns.map((conn) => conn.quit()));
    this.conns = [];
    this.log.info("jobs worker stopped");
  }

  private logRedisError(cause: unknown, hint: string): void {
    const now = Date.now();
    if (now - this.lastRedisError > 5000) {
      this.lastRedisError = now;
      this.log.error({ cause }, hint);
    }
  }

  private async pollLoop(conn: RedisLike, group: PollerGroup): Promise<void> {
    const key = jobsQueueKey(this.tenant, group.queue);
    while (this.running) {
      let answer: [string, string] | null = null;
      try {
        answer = await conn.blpop(key, this.pollTimeoutSeconds);
      } catch (cause) {
        if (!this.running) {
          break;
        }
        this.logRedisError(cause, "poller turn failed");
        await delay(this.reconnectDelayMs);
        continue;
      }

      const raw = answer?.[1];
      if (raw === undefined) {
        continue;
      }

      await this.process(raw, group);
    }
  }

  private async process(raw: string, group: PollerGroup): Promise<void> {
    const payload = parseJobPayload(raw);
    if (payload === null) {
      this.log.warn({ queue: group.queue, payload: raw.slice(0, 200) }, "corrupt job payload dropped");
      return;
    }

    const started = Date.now();
    let claimed;
    try {
      claimed = await this.client.claim(payload.jobId, payload.scheduledAt);
    } catch (cause) {
      // The row keeps its state: the dispatcher reschedules it or the lease
      // aborts it. Never treat a failed claim as a skip.
      this.log.warn({ cause, jobId: payload.jobId }, "job claim failed");
      return;
    }

    if (claimed.action === "skip" || claimed.name === undefined) {
      this.log.debug({ jobId: payload.jobId }, "job claim skipped");
      return;
    }

    const name = claimed.name;
    const handler = this.handlers[name];
    if (handler === undefined) {
      await this.failJob(payload.jobId, {
        error: {
          type: "internal",
          code: "no-job-handler",
          hint: `no handler registered for job ${name}`,
        },
        retriable: false,
      });
      return;
    }

    const controller = new AbortController();
    const context: JobContext = {
      jobId: payload.jobId,
      name,
      queue: group.queue,
      signal: controller.signal,
      abort: (reason) => controller.abort(reason),
    };

    try {
      this.inflight.set(payload.jobId, controller);
      if (!this.running) {
        // The claim landed after `stop` began: do not start new work.
        controller.abort("shutdown" satisfies JobAbortReason);
      }
      const result = await handler(claimed.params, context);

      if (controller.signal.aborted) {
        await this.reportAbort(context, started);
        return;
      }

      await this.client.complete(payload.jobId, result ?? null);
      this.log.info({ jobId: payload.jobId, name, elapsed: Date.now() - started }, "job completed");
    } catch (cause) {
      if (controller.signal.aborted) {
        await this.reportAbort(context, started);
        return;
      }

      await this.failJob(payload.jobId, this.errorReport(cause));
      this.log.info({ jobId: payload.jobId, name, elapsed: Date.now() - started, outcome: "failed" }, "job failed");
    } finally {
      this.inflight.delete(payload.jobId);
    }
  }

  /**
   * Drop the work of an aborted job. A `cancelled` job is already terminal
   * (the backend marked it), so nothing is written; a `shutdown` job is
   * reported as retriable so the substrate schedules another attempt.
   */
  private async reportAbort(context: JobContext, started: number): Promise<void> {
    this.log.info(
      {
        jobId: context.jobId,
        name: context.name,
        reason: context.signal.reason,
        elapsed: Date.now() - started,
      },
      "job aborted, outcome dropped"
    );

    if (context.signal.reason === "shutdown") {
      await this.reportShutdown(context.jobId);
    }
  }

  private errorReport(cause: unknown): { error: JobError; retriable: boolean } {
    if (cause instanceof ProcessingError) {
      const body = cause.errorBody;
      return {
        error: { ...body, hint: body.hint ?? body.code },
        retriable: false,
      };
    }
    this.log.error({ cause }, "unexpected job handler error");
    return {
      error: { type: "internal", code: "processing-error", hint: "Internal processing error" },
      retriable: true,
    };
  }

  private async failJob(jobId: string, report: { error: JobError; retriable: boolean }): Promise<void> {
    try {
      await this.client.fail(jobId, report.error, report.retriable);
    } catch (cause) {
      // The row stays running until its lease: the dispatcher aborts it and
      // the next attempt (if any) is up to the substrate.
      this.log.error({ cause, jobId }, "job fail report failed");
    }
  }

  /**
   * Report a job the worker cannot finish as a retriable failure. The
   * handler may still be running; `abandoned` keeps every path from settling
   * the same row twice, so the attempt belongs to the substrate.
   */
  private async reportShutdown(jobId: string): Promise<void> {
    if (this.abandoned.has(jobId)) {
      return;
    }
    this.abandoned.add(jobId);

    await this.failJob(jobId, {
      error: {
        type: "internal",
        code: "worker-shutdown",
        hint: "the worker stopped before the job finished",
      },
      retriable: true,
    });
    this.log.warn({ jobId }, "job reported as retriable on shutdown");
  }
}
