# Backend jobs

## Start in the code

- Definitions and lifecycle: `app.jobs`, `app.main` (`::jobs/defs`). Dispatch, execution and cron: `app.worker.dispatcher`, `app.worker`, `app.worker.cron`. Retention and resources: `app.jobs.gc`, `app.jobs.storage`, `mem:backend/storage`.
- User-facing RPCs: `app.rpc.commands.jobs`; file import/export: `mem:backend/binfile`. Deployment and queue topology: `mem:prod-infra/core`. DB transaction semantics: `mem:backend/subtleties`. Tests: `backend-tests.jobs-test`, `backend-tests.worker-test`, and the tests beside each job.

## Boundaries

- A job-def closes over its own dependencies. A handler receives context and business params, never the runner cfg. A job-def must not reference `::jobs/defs` through Integrant: the registry already depends on the definitions. Don't add routing or retry state to public context; it belongs to dispatch or execution, not the job's business contract.
- `submit` joins the caller transaction because creation is part of the caller's work. Claim, retry and terminal transitions own their transactions because they report work the caller cannot roll back. A job must commit before an independent claimant can see it. A transition writes its event with the state change; a no-op writes neither event nor metric. Keep job foreign keys deferrable.
- Submit keys include tenant, name, queue and label; rely on the partial unique indexes, not a read-before-write check. `skip-if-active` excludes live exclusive jobs; dedupe discards a conflict with an unstarted job; debounce refreshes that pending row and retains its ID. Opted-in jobs do not conflict with plain jobs just because they share a label. The options that require distinct conflict targets cannot be combined.
- Postgres owns the lifecycle; Redis delivery is not proof of a claim. Dispatch and recovery must stay tenant-scoped. Lost scheduled jobs and expired running jobs are terminal, not requeued; sending them back to `new` can violate dedupe. Long-running work must heartbeat; a beat that finds a terminal or missing job raises an internal interruption, not a retryable failure. Progress can be throttled, so each milestone must make sense without the previous one.
- Job events are durable; progress is not a Redis value or mutable column. Dispatcher aborts batch-write terminal events without a Pub/Sub notification; inspect logs or `penpot_jobs_total_ms_count{outcome="aborted"}` for either abort cause. Don't assume a client that missed a WebSocket event can replay the event log through the jobs RPC.
- `complete` is the shared result validation boundary for the runner and external completions. The job-def owns the JSON result schema; declare it when UUIDs (including map keys) or other typed values must round-trip. Read job errors and progress through their job decoders rather than generic JSON decoding.

## User jobs and metrics

- Do not infer the owner, resource or expiry from business params. A public job belongs to its profile; an absent job and another profile's job both answer `not-found`. Registry metadata such as job family and routing is interpreted by consumers, not the substrate. Family quotas count live jobs from registered definitions, not a fixed list of names.
- Internal retention and user expiry are separate. Job GC must retire a row and arrange cleanup of its resource together; a bare delete would cascade events without touching the resource. Use the bucket's owner and lifetime rules in `mem:backend/storage`.
- Metric definitions live in `app.jobs.metrics`. Keep labels bounded (no IDs, payloads or exception text). A histogram's `_count` is the counter for that event; dispatch and claim measure different events. Claim wait includes the dispatcher gap, not just time in Redis. Use SQL for backlog rather than adding a gauge to the write path; debounce does not reset `created_at`.
- Record SQL-backed metrics via `db/after-commit` inside the writing transaction. Wire a metrics instance into the cfg before writing: a missing dependency discovered only in a callback may be swallowed. A user-owned event writer also needs a message bus on its cfg.
