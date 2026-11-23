# Backend Subtleties

Historical index. Start at `mem:backend/core` for current guidance, or follow the topic links below.

## RPC exposure and wrappers

- Command registration, validation and wrapper order: `mem:backend/http-rpc`.

## Concurrency limiter (CLIMIT)

- Limit declarations, acquisition order and queue diagnosis: `mem:backend/rpc-concurrency`.

## DB helpers

- Transaction ownership, callbacks and advisory locks: `mem:backend/db-transactions`.

## Workers and cron

- Job lifecycle, recovery and cron overlap: `mem:backend/jobs`.
- Startup failures in injected component schemas: `mem:backend/integrant-schemas`.

## Config and HTTP/session middleware

- Environment values and PostgreSQL connection settings: `mem:backend/config`.
- Request parsing, response negotiation and session auth: `mem:backend/http-rpc`.

## HTTP server self-metrics

- HTTP listener and worker sampling, missing samples and JVM runtime requirements: `mem:backend/metrics`.

## Metrics recording

- Collector failures, metrics wiring and transaction boundaries: `mem:backend/metrics`.

## Log-derived metrics

- What the reporter counts and how its labels differ from Loki: `mem:backend/metrics`.

## Storage and media

- Local and remote processing, upload validation and SVG handling: `mem:backend/media`.
- Buckets, object lifecycle and cleanup: `mem:backend/storage`.
- File trim and cascade event fields: `mem:audit-log`.

## File data persistence

- Read-time migrations, fragments, snapshots and restore: `mem:backend/file-data/reads-and-versions`.
- Partial save acceptance and failure handling: `mem:backend/file-data/save`.
- Blob references, offload and partition-aware storage lookups: `mem:backend/file-data-storage`.
