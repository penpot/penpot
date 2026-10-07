import { Worker } from "node:worker_threads";
import { ProcessingError } from "./middleware/error-handler.js";
import { createLogger } from "./logger.js";
import type { AppError } from "./types.js";

const logger = createLogger("svg-pool");

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
}

type SvgWorkerResult = { ok: true; clean: string } | { ok: false; statusCode: number; body: AppError };

let state: PoolState | null = null;
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
  state = { options, workers: [], queue: [], stopped: false };
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
  state = null;

  for (const job of current.queue.splice(0)) {
    job.reject(stoppedError());
  }

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
  if (current === null || current.stopped) {
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
  const job = pooled.job;
  if (job === null) {
    return;
  }
  clearJobTimer(job);
  pooled.job = null;

  const result = value as SvgWorkerResult;
  if (result.ok) {
    job.resolve(result.clean);
  } else {
    if (result.body.code === "svg-sanitization-failed") {
      logger.error("svg worker reported an unexpected sanitization failure");
    }
    job.reject(new ProcessingError(result.statusCode, result.body));
  }
  dispatch();
}

function onWorkerError(pooled: PooledWorker, err: Error): void {
  logger.error({ err }, "svg worker error");
  failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-worker-failed" }));
}

function onWorkerExit(pooled: PooledWorker, code: number): void {
  if (pooled.terminating) {
    return;
  }
  logger.error({ code }, "svg worker exited unexpectedly");
  failWorker(pooled, new ProcessingError(503, { type: "internal", code: "svg-worker-exited" }));
}

/** Reject the worker's in-flight job (if any) and replace the worker. */
function failWorker(pooled: PooledWorker, err: ProcessingError): void {
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
  if (current !== null && !current.stopped) {
    const index = current.workers.indexOf(pooled);
    if (index !== -1) {
      current.workers[index] = spawnWorker();
    }
    dispatch();
  }
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
