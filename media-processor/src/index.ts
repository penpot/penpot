import { Redis } from "ioredis";
import { BackendClient } from "./backend-client.js";
import { loadConfig } from "./config.js";
import { createHealthServer } from "./health.js";
import { JobsClient } from "./jobs-client.js";
import { JobsWorker, type RedisLike } from "./jobs-worker.js";
import { makeMediaHandlers } from "./jobs/media.js";
import { initLogger, logActiveTransports } from "./logger.js";
import { configureRuntime } from "./runtime.js";

const config = loadConfig();
const log = initLogger(config);
logActiveTransports(log);
configureRuntime(config);

const backend = new BackendClient({
  backendUri: config.backendUri,
  sharedKey: config.sharedKey,
  maxFileSize: config.maxFileSize,
  timeoutMs: config.backendTimeout,
});

const jobs = new JobsClient({
  backendUri: config.backendUri,
  sharedKey: config.sharedKey,
  timeoutMs: config.backendTimeout,
});

let lastRedisError = 0;

function logRedisError(cause: unknown): void {
  const now = Date.now();
  if (now - lastRedisError > 5000) {
    lastRedisError = now;
    log.error({ cause }, "redis connection error");
  }
}

/**
 * One connection per poller: the blocking `BLPOP` holds it for the wait and
 * the poller awaits its handler before popping again, so the connections are
 * the concurrency ceiling.
 */
function createConnection(): RedisLike {
  const conn = new Redis(config.redisUri);
  conn.on("error", logRedisError);
  return conn as unknown as RedisLike;
}

const worker = new JobsWorker({
  tenant: config.tenant,
  client: jobs,
  handlers: makeMediaHandlers({ backend, jobs }),
  fastQueue: config.jobsFastQueue,
  slowQueue: config.jobsQueue,
  fastConcurrency: config.jobsFastConcurrency,
  slowConcurrency: config.jobsConcurrency,
  createConnection,
});

const health = createHealthServer();

async function main(): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const onError = (cause: Error): void => reject(cause);
    health.once("error", onError);
    health.listen(config.port, config.host, () => {
      health.off("error", onError);
      resolve();
    });
  });

  await worker.start();
  log.info(
    {
      host: config.host,
      port: config.port,
      tenant: config.tenant,
      fastQueue: config.jobsFastQueue,
      slowQueue: config.jobsQueue,
      fastConcurrency: config.jobsFastConcurrency,
      slowConcurrency: config.jobsConcurrency,
    },
    "media worker started"
  );
}

let stopping = false;

async function shutdown(signal: string): Promise<void> {
  if (stopping) {
    return;
  }
  stopping = true;
  log.info({ signal }, "media worker shutting down");
  await worker.stop();
  await new Promise<void>((resolve) => health.close(() => resolve()));
  await new Promise<void>((resolve) => log.flush(() => resolve()));
  // pino transports keep handles alive; flush first, then exit deliberately.
  process.exit(0);
}

process.on("SIGTERM", () => void shutdown("SIGTERM"));
process.on("SIGINT", () => void shutdown("SIGINT"));

main().catch((cause) => {
  log.error({ cause }, "media worker failed to start");
  process.exit(1);
});
