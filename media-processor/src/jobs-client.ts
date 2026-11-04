import { ERROR_SNIPPET_BYTES, isRecord, joinUri } from "./http.js";
import { createLogger } from "./logger.js";

/**
 * Client of the durable job ledger (`/api/management/methods/*`).
 *
 * The worker never touches the database: `claim-job`,
 * `report-job-progress`, `complete-job` and `fail-job` are the whole
 * protocol. They authenticate with the media shared key and answer the
 * action the worker must take:
 *
 * - `claim`: `run` (with the job name and params) or `skip`.
 * - `progress`: `run` or `skip` (the job is no longer active).
 * - `complete`: `run` or `skip`.
 * - `fail`: `retry` when the substrate scheduled another attempt, `run`
 *   when it is terminal, `skip` when the row is already terminal.
 *
 * An HTTP error (403 for a key that is not allowed, a 5xx, a timeout) is
 * never an action: it throws, so the poller can log it and keep the job for
 * its lease instead of believing it was skipped.
 */

export type JobsAction = "run" | "skip";
export type FailAction = "run" | "retry" | "skip";

/**
 * A progress milestone of a running job. The backend validates it: `stage`
 * is a short keyword-like name and every counter has a non-negative
 * `current` and an optional positive `total`.
 */
export interface JobProgress {
  stage: string;
  counters?: Record<string, { current: number; total?: number }>;
}

export interface JobError {
  type: string;
  code: string;
  hint: string;
  [key: string]: unknown;
}

export interface JobsClientOptions {
  backendUri: string;
  sharedKey: string;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
}

export interface ClaimedJob {
  action: JobsAction;
  name?: string;
  params?: unknown;
}

const log = createLogger("jobs-client");
const DEFAULT_TIMEOUT_MS = 60_000;

/**
 * A management call that did not answer: transport, timeout, HTTP error,
 * malformed body or an action outside the contract. `status` is 0 when the
 * request never got a response.
 */
export class JobsClientError extends Error {
  public readonly method: string;
  public readonly status: number;

  public constructor(method: string, status: number, message: string) {
    super(message);
    this.method = method;
    this.status = status;
  }
}

export class JobsClient {
  private readonly backendUri: string;
  private readonly sharedKey: string;
  private readonly timeoutMs: number;
  private readonly fetchImpl: typeof fetch;

  public constructor(options: JobsClientOptions) {
    this.backendUri = options.backendUri;
    this.sharedKey = options.sharedKey;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.fetchImpl = options.fetchImpl ?? fetch;
  }

  private get headers(): Record<string, string> {
    // The backend answers transit by default: ask for the JSON dialect and
    // speak its key names (camelCase), the ones the JSON writer produces.
    return {
      "X-Shared-Key": `media-processor ${this.sharedKey}`,
      Accept: "application/json",
      "content-type": "application/json",
    };
  }

  /**
   * `POST /api/management/methods/<method>` and answer the parsed body. A
   * non-2xx status, a non-JSON body or a timeout throws: never `skip`.
   */
  private async call(method: string, params: Record<string, unknown>): Promise<Record<string, unknown>> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    const url = joinUri(this.backendUri, `api/management/methods/${method}`);

    try {
      const response = await this.fetchImpl(url, {
        method: "POST",
        headers: this.headers,
        body: JSON.stringify(params),
        signal: controller.signal,
      });

      const text = await response.text();
      if (!response.ok) {
        log.warn(
          { method, status: response.status, body: text.slice(0, ERROR_SNIPPET_BYTES) },
          "management call failed"
        );
        throw new JobsClientError(method, response.status, `management call failed with status ${response.status}`);
      }

      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        throw new JobsClientError(method, response.status, "management answered a non-JSON body");
      }

      if (!isRecord(parsed)) {
        throw new JobsClientError(method, response.status, "management answered a non-object body");
      }

      return parsed;
    } catch (cause) {
      if (cause instanceof JobsClientError) {
        throw cause;
      }
      if (controller.signal.aborted) {
        throw new JobsClientError(method, 0, `management call timed out after ${this.timeoutMs}ms`);
      }
      log.warn({ method, cause }, "management call failed");
      throw new JobsClientError(method, 0, "management call failed");
    } finally {
      clearTimeout(timer);
    }
  }

  private action(body: Record<string, unknown>, method: string, allowed: readonly string[]): string {
    const action = body.action;
    if (typeof action !== "string" || !allowed.includes(action)) {
      throw new JobsClientError(method, 0, `unexpected action from management: ${String(action)}`);
    }
    return action;
  }

  /**
   * Claim the job of a queue payload. A `run` always carries the job name;
   * a claim without one is a contract mismatch, not a job to execute.
   */
  public async claim(jobId: string, scheduledAt: string): Promise<ClaimedJob> {
    const method = "claim-job";
    const body = await this.call(method, { jobId, scheduledAt });
    const action = this.action(body, method, ["run", "skip"]) as JobsAction;

    if (action === "skip") {
      return { action };
    }
    if (typeof body.name !== "string") {
      throw new JobsClientError(method, 0, "claim answered without a job name");
    }
    return { action, name: body.name, params: body.params };
  }

  public async progress(jobId: string, progress: JobProgress): Promise<JobsAction> {
    const method = "report-job-progress";
    const body = await this.call(method, { jobId, progress });
    return this.action(body, method, ["run", "skip"]) as JobsAction;
  }

  public async complete(jobId: string, result: unknown, resourceId?: string): Promise<JobsAction> {
    const method = "complete-job";
    const params: Record<string, unknown> = { jobId, result };
    if (resourceId !== undefined) {
      params.resourceId = resourceId;
    }
    const body = await this.call(method, params);
    return this.action(body, method, ["run", "skip"]) as JobsAction;
  }

  public async fail(jobId: string, error: JobError, retriable = false): Promise<FailAction> {
    const method = "fail-job";
    const body = await this.call(method, { jobId, error, retriable });
    return this.action(body, method, ["run", "retry", "skip"]) as FailAction;
  }
}
