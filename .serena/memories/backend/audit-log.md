# Backend Audit Log

Penpot records what users do as events in the Postgres `audit_log` table. There are two producers (the backend RPC layer and the frontend app) and four consumers (webhooks, error reporters, telemetry shipping, and the Nexus archive). Everything below follows that flow: purpose, storage, producers, consumers, archival.

## Purpose

- The audit log answers "who did what, when, from where": every RPC mutation and selected frontend actions become a row with `name`, `type`, `profile-id`, `ip-addr`, `props` and `context`. Product analytics, abuse investigation and compliance exports all read from here, so keep events truthful and never put secrets in `props`.
- It is also the trigger bus for side effects: the same event object fans out to webhooks, error reporting and telemetry without the RPC handler knowing. New features should reuse this bus instead of building parallel notification paths.

## Storage

- Live `audit_log` columns: `id` uuid PK default `gen_random_uuid()`; `name`/`type` text NOT NULL; `created_at` timestamptz NOT NULL default `now()` (server time, the source of truth); `tracked_at` timestamptz default `now()` (client-claimed time, corrected on ingest); `profile_id` uuid NOT NULL; `source` text telling full rows (`backend`/`frontend`) apart from anonymized copies (`telemetry:backend`/`telemetry:frontend`); `ip_addr` inet; `props`/`context` jsonb holding transit-encoded maps; `archived_at` timestamptz set once Nexus acknowledges the row.
- Indexes: PK on `(id)`; partial `created_at WHERE archived_at IS NULL` serving the archive scan; partial `archived_at WHERE archived_at IS NOT NULL` serving the GC; `(source, created_at)` serving the telemetry scan. Each consumer has its own index, so a slow consumer never blocks the others.

## Backend producers (`app.loggers.audit`)

- Most backend events need no manual code: `wrap-audit` in `app.rpc` runs after every RPC handler when `:webhooks`, `:audit-log` or `:telemetry` is on (unless the command sets `::audit/skip`) and builds the event via `prepare-rpc-event`. The event name defaults to the command name (prefixed with `<module>-` outside `main`), props default to the request params, and timestamps come from the server request time.
- Commands customize through result metadata (`rph/with-meta`): `::audit/replace-props` swaps the props wholesale (auth commands use `profile->props` so a register event carries the profile, not the password), `::audit/props` merges extras, `::audit/context`/`profile-id`/`name`/`type` override the defaults. `clean-props` always strips nils, qualified keys and `:session-id/:password/:old-password/:token/:client-secret` as a last line of defense.
- `submit` is the normal entry point (fills defaults, validates `schema:event`, runs inside `tx-run!`, logs failures without failing the RPC). `insert` is the low-level one for CLI/helpers and the webhook subsystem: direct write, no webhook/telemetry fan-out, silent unless `:audit-log` is on. Boot emits `trigger/instance-start` from `setup/props` so every restart is visible in the log.

## Consumers I: webhooks (`app.loggers.webhooks`)

- Webhooks are the first dependent: when an event carries `::webhooks/event?`, `process-event` (worker task `:process-webhook-event`) finds the team's active webhooks from the event props (`team-id`, else `project-id`, else `file-id`), records a `trigger webhook` row, and enqueues one `:run-webhook` delivery per match. Batching and dedupe come from the audit event itself (`batch-key` + `batch-timeout`), not from webhook config.
- `:run-webhook` POSTs the event in the webhook's `mtype` (JSON camelCase, transit, or form-encoded), logs each attempt in `webhook-delivery`, and disables the webhook after 3 consecutive errors. Delivery problems never touch the audit row itself.

## Consumers II: error reporters (`app.loggers.database`, `app.loggers.mattermost`)

- Both reporters listen for backend `:error` log records and for frontend crash events (recognized by the `::audit/event` marker), through a sliding-buffer channel so a flood of errors cannot stall the app. The database reporter persists them into `server-error-report` (source 4 = audit-log origin); the Mattermost reporter forwards a short notification to `:error-report-webhook` when configured.
- Consequence for producers: crash reports only exist if the frontend collector is running and `push-audit-events` accepts `unhandled-exception`/`exception-page` events. Disabling the whole pipeline also blinds error reporting from the frontend.

## Consumers III: telemetry (`app.loggers.audit` + `app.tasks.telemetry`)

- Telemetry reuses the same table with anonymized shadow rows (`source LIKE 'telemetry:%'`): day-truncated timestamps, `0.0.0.0` IPs, props reduced to uuid/boolean/number values plus a few allowlisted fields (`lang`, `auth-backend`, derived `email-domain`, never raw emails), and a minimal context allowlist. Both full and shadow rows can coexist per event; that duplication is intentional.
- The telemetry cron ships shadow rows to `:telemetry-uri` as JSON in 10k batches, deletes them on success, and purges leftovers older than 7d. Nothing is collected or sent on official hosts (`telemetry-excluded?` covers `penpot.app`/`penpot.dev`).

## Frontend ingestion (`app.rpc.commands.audit`, `app.main.data.event`)

- The browser cannot write to the table directly; it POSTs transit batches to `push-audit-events`, which stamps server `id`, session `profile-id`, request ip and server `created-at`, and distrusts the client clock (future or >1h-lagging `tracked-at` is reset, original preserved in context). The endpoint is a no-op without `:audit-log`/`:telemetry` or on a read-only pool.
- The in-browser collector (`app.main.data.event`) only starts after `get-enabled-flags` confirms the backend wants events. It turns Potok events and explicit `ev/event` calls (nitrate membership changes, workspace file stats, crash reports) into a capped buffer (1024, chunks of 100, 2s debounce, current profile only) and sends fire-and-forget. `skip-audit?` exists for resumed dashboard actions so one user gesture is not counted twice.
- Because collection is best-effort and includes `PerformanceObserver` noise (`performance-*` triggers), backend tests must never assert exact frontend event counts.

## Archival to Nexus and retention

- Long-term storage lives outside Penpot in Nexus. Every 5m the `:audit-log-archive` cron takes chunks of 128 unarchived rows (`FOR UPDATE SKIP LOCKED`), POSTs them as transit `{:events [...]}` authenticated with `x-shared-key: "nexus <key>"` (`:nexus-shared-key`, else derived from the instance secret), and marks `archived_at=now()` only on HTTP 204, in the same transaction. Anything else is retried on the next run; a missing URI with the flag on raises `:task-not-configured`.
- Every 5m the `:audit-log-gc` cron deletes all archived rows (no age filter), so archive must run before GC or data ships never. Cron dedup is best-effort (`mem:prod-infra/core`): two backends can fire the archiver twice, which is why the Nexus endpoint must be idempotent and the DB only marks acknowledged rows.
- Flags live in `common/flags.cljc` varia and are enabled as `PENPOT_FLAGS=enable-<name>`: `:audit-log`, `:audit-log-archive`, `:audit-log-gc`, `:audit-log-logger` (structured `app.audit` log). `:telemetry-enabled` config auto-adds `:enable-telemetry`.

## Tests

- `backend_tests/rpc-audit-test.clj` exercises the whole backend path (full-row insert, telemetry-only and dual-row modes, `submit*`, no-op without flags, `insert` gating, `prepare-rpc-event` resolution) with `with-redefs [cf/flags #{...}]` against real `audit_log` rows.
- Other RPC suites mock `app.loggers.audit/submit` (nil return; `helpers.clj` stubs it globally) and assert on `:call-args-list`; any new command that must (or must not) emit an event needs the same treatment.
