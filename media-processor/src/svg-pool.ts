import { Worker } from "node:worker_threads";
import { ProcessingError } from "./middleware/error-handler.js";
import { createLogger } from "./logger.js";
import type { AppError } from "./types.js";

const logger = createLogger("svg-pool");

// A worker that fails before it ever answers (a missing bundle, a broken loader)
// must not respawn forever. After this many consecutive spawn failures the slot
// is dropped; with no workers left the pool is degraded and answers 503.
const MAX_SPAWN_FAILURES = 5;

/**
 * The slice of `worker_threads.Worker` the pool uses. Keeping it narrow lets
 * tests inject a fake worker and exercise the queue, the timeout and the
 * failure handling without real threads.
 */
export interface SvgWorker {
  postMessage(value: unknown): void;
  terminate(): Promise<number>;
  on(event: "message", listener: (value: unknown) => void): this;
  on(event: "error", listener: (err: Error) => void): this;
  on(event: "exit", listener: (code: number) => void): this;
}

export interface SvgPoolOptions {
  workers: number;
  maxOldGenerationSizeMb: number;
  timeout: number;
}

interface Job {
  svg: string;
  resolve: (clean: string) => void;
  reject: (err: Error) => void;
  timer: NodeJS.Timeout | null;
}

interface PooledWorker {
  worker: SvgWorker;
  job: Job | null;
  terminating: boolean;
}

interface PoolState {
  options: SvgPoolOptions;
  workers: PooledWorker[];
  queue: Job[];
  stopped: boolean;
  degraded: boolean;
}

type SvgWorkerResult = { ok: true; clean: string } | { ok: false; statusCode: number; body: AppError };

let state: PoolState | null = null;
let spawnFailures = 0;
let createWorker: () => SvgWorker = defaultWorkerFactory;

function workerUrl(): URL {
  // Under `tsx` the module is a `.ts` source and the worker must be loaded as
  // `.ts`; in the bundle it is the emitted `.js`. `import.meta.url` tells them
  // apart.
  const extension = import.meta.url.endsWith(".ts") ? "ts" : "js";
  return new URL(`./svg-worker.${extension}`, import.meta.url);
}

function defaultWorkerFactory(): SvgWorker {
  const options = state!.options;
  return new Worker(workerUrl(), {
    // Node inherits the parent flags by default; setting it explicitly keeps
    // the dev loader (`tsx`) available in the worker.
    execArgv: process.execArgv,
    resourceLimits: { maxOldGenerationSizeMb: options.maxOldGenerationSizeMb },
  }) as unknown as SvgWorker;
}

/** Replace the worker factory. Tests use it to inject a fake; `null` restores the real one. */
export function setSvgWorkerFactory(factory: (() => SvgWorker) | null): void {
  createWorker = factory ?? defaultWorkerFactory;
}

/** Create the pool. `workers <= 0` disables it (the sanitizer runs inline). */
export function configureSvgPool(options: SvgPoolOptions): void {
  if (state !== null) {
    throw new Error("svg pool is already configured");
  }
  spawnFailures = 0;
  state = { options, workers: [], queue: [], stopped: false, degraded: false };
  for (let i = 0; i < options.workers; i++) {
    state.workers.push(spawnWorker());
  }
}

export function getSvgPoolOptions(): SvgPoolOptions | null {
  return state?.options ?? null;
}

/** Queue an SVG for sanitization in a worker. */
export function runInSvgPool(svg: string): Promise<string> {
  const current = state;
  if (current === null || current.stopped) {
    return Promise.reject(stoppedError());
  }
  if (current.options.workers <= 0) {
    return Promise.reject(disabledError());
  }
  if (current.degraded) {
    return Promise.reject(degradedError());
  }
  return new Promise<string>((resolve, reject) => {
    current.queue.push({ svg, resolve, reject, timer: null });
    dispatch();
  });
}

/** Terminate every worker and reject whatever is pending. */
export async function shutdownSvgPool(): Promise<void> {
  const current = state;
  if (current === null) {
    return;
  }
  current.stopped = true;
  spawnFailures = 0;
  state = null;

  rejectQueue(current, stoppedError());

  const workers = current.workers;
  current.workers = [];
  await Promise.all(
    workers.map((pooled) => {
      pooled.terminating = true;
      const job = pooled.job;
      pooled.job = null;
      if (job !== null) {
        clearJobTimer(job);
        job.reject(stoppedError());
      }
      return pooled.worker.terminate().catch(() => 0);
    })
  );
}

function spawnWorker(): PooledWorker {
  const worker = createWorker();
  const pooled: PooledWorker = { worker, job: null, terminating: false };
  worker.on("message", (value) => onMessage(pooled, value));
  worker.on("error", (err) => onWorkerError(pooled, err));
  worker.on("exit", (code) => onWorkerExit(pooled, code));
  return pooled;
}

function dispatch(): void {
  const current = state;
  if (current === null || current.stopped || current.degraded) {
    return;
  }
  while (current.queue.length > 0) {
    const pooled = current.workers.find((w) => w.job === null && !w.terminating);
    if (pooled === undefined) {
      return;
    }
    const job = current.queue.shift()!;
    pooled.job = job;
    job.timer = setTimeout(() => {
      job.timer = null;
      failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-timeout" }));
    }, current.options.timeout);
    pooled.worker.postMessage(job.svg);
  }
}

function onMessage(pooled: PooledWorker, value: unknown): void {
  if (!isWorkerResult(value)) {
    logger.error("svg worker sent a malformed message");
    failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-worker-failed" }), true);
    return;
  }

  // A well-formed answer proves the worker loaded, so the spawn streak is over.
  spawnFailures = 0;

  const job = pooled.job;
  if (job === null) {
    return;
  }
  clearJobTimer(job);
  pooled.job = null;

  if (value.ok) {
    job.resolve(value.clean);
  } else {
    if (value.body.code === "svg-sanitization-failed") {
      logger.error("svg worker reported an unexpected sanitization failure");
    }
    job.reject(new ProcessingError(value.statusCode, value.body));
  }
  dispatch();
}

function onWorkerError(pooled: PooledWorker, err: Error): void {
  logger.error({ err }, "svg worker error");
  failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-worker-failed" }), true);
}

function onWorkerExit(pooled: PooledWorker, code: number): void {
  if (pooled.terminating) {
    return;
  }
  logger.error({ code }, "svg worker exited unexpectedly");
  failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-worker-exited" }), true);
}

/**
 * Reject the worker's in-flight job (if any) and replace the worker. When the
 * failure is a spawn failure and the streak passes `MAX_SPAWN_FAILURES`, the
 * slot is dropped instead of respawned; a pool left with no workers is degraded
 * and answers 503 for every request.
 */
function failWorker(pooled: PooledWorker, err: ProcessingError, spawnFailure = false): void {
  if (pooled.terminating) {
    return;
  }
  pooled.terminating = true;

  const job = pooled.job;
  pooled.job = null;
  if (job !== null) {
    clearJobTimer(job);
    job.reject(err);
  }

  void pooled.worker.terminate().catch(() => 0);

  const current = state;
  if (current === null || current.stopped) {
    return;
  }

  if (spawnFailure) {
    spawnFailures++;
  }

  const index = current.workers.indexOf(pooled);
  if (spawnFailure && spawnFailures > MAX_SPAWN_FAILURES) {
    if (index !== -1) {
      current.workers.splice(index, 1);
    }
    if (current.workers.length === 0) {
      current.degraded = true;
      logger.error("svg pool degraded: no worker could be started");
      rejectQueue(current, degradedError());
    }
    return;
  }

  if (index !== -1) {
    current.workers[index] = spawnWorker();
  }
  dispatch();
}

function rejectQueue(current: PoolState, err: ProcessingError): void {
  for (const job of current.queue.splice(0)) {
    clearJobTimer(job);
    job.reject(err);
  }
}

function isWorkerResult(value: unknown): value is SvgWorkerResult {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const result = value as { ok?: unknown; clean?: unknown; statusCode?: unknown; body?: unknown };
  if (result.ok === true) {
    return typeof result.clean === "string";
  }
  if (result.ok === false) {
    return typeof result.statusCode === "number" && typeof result.body === "object" && result.body !== null;
  }
  return false;
}

function clearJobTimer(job: Job): void {
  if (job.timer !== null) {
    clearTimeout(job.timer);
    job.timer = null;
  }
}

function stoppedError(): ProcessingError {
  return new ProcessingError(503, { type: "internal", code: "svg-pool-stopped" });
}

function degradedError(): ProcessingError {
  return new ProcessingError(503, { type: "internal", code: "svg-pool-degraded" });
}

function disabledError(): ProcessingError {
  return new ProcessingError(503, { type: "internal", code: "svg-pool-disabled" });
}
