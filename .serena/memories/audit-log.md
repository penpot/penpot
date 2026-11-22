# Audit Log and event processing

The audit log records what Penpot observed: who made a request, what they asked to do, and when it happened. It also carries browser events for usage analytics and crash reporting. Those events are useful, but a browser can close or lose its connection before sending them. Do not use their absence as proof that something did not happen.

## 1. Sources and trust

| Channel | Who sets the actor | What the event tells you |
|---|---|---|
| Penpot backend RPC | The server uses the caller, an explicit command override, or `uuid/zero` when no actor is known. | The server ran the command. Check the event's outcome before treating it as a change of state. |
| Public `push-audit-events` | Penpot uses the session, never an actor supplied in the event. | The Penpot or admin-console browser reported an action. Delivery is best-effort. |
| Management `push-audit-events` | The authenticated admin-console service supplies the actor. | A trusted service reported an action. Authentication identifies the service, not whether its business action and the event committed together. |

The management channel uses `POST /api/management/methods/push-audit-events` with `x-shared-key: "admin-console <key>"` and events carrying `{name, profileId, props?, context?}` in JSON. It uses server request time, not the public endpoint's client timestamp. The public channel uses `POST /api/main/methods/push-audit-events` with a user session and events carrying `{name, type, timestamp, props, context?}`. Both browsers use the same public RPC. The Nitrate senders live in the private Nitrate repo; these are the contracts Penpot receives.

`source` distinguishes channels: management events are `backend`, browser events are `frontend`, and telemetry copies use `telemetry:backend` or `telemetry:frontend`. It is not an outcome. For example, `update-file` can record a rejected save without a change to the file.

```clojure
;; Selected fields: profile-id names the actor, props.id names the new file.
{:name "create-file" :type "action" :source "backend"
 :profile-id actor-id
 :props {:id file-id :project-id project-id :team-id team-id}
 :context {:initiator "app" :request-id request-id}}
```

`props` describes the action; `context` describes how Penpot captured it. `created-at` is server time. In public ingest, `tracked-at` comes from the browser: Penpot replaces future timestamps and timestamps over an hour old with server time, keeping the original in `:original-tracked-at` before any telemetry filtering.

### Context field guide: `initiator`

For backend RPCs, `:initiator` is the shared-key id authenticated by HTTP middleware, or `"app"` for ordinary requests. For public ingest, Penpot overwrites the supplied value based on `x-client`: the admin-console product (including the legacy `penpot-nitrate` alias) maps to `"admin-console"`; Penpot and unknown products map to `"app"`. A caller controls that header. Use the label for analytics, not authorization or proof of which app sent the event. The session still sets the actor.

Do not confuse `:client-event-origin` (the `x-event-origin` header on an RPC request) with `:event-origin` (context on a browser event). The former can help explain which UI triggered a backend action. The latter should not carry a backend business distinction.

## 2. Emitting events

### Backend events

The RPC wrapper emits after a command returns when full-row persistence, telemetry or webhooks is enabled. Use `::audit/skip` for a command that should not emit. Use result metadata to add `::audit/props`, replace request props with `::audit/replace-props`, or set an explicit name, type or context. **Never put secrets in props or context.** `clean-props` drops some known sensitive keys on the RPC path; it is not a general privacy filter for every producer.

Attribute the event to the **actor**, not an arbitrary `:profile-id` in the response. An invitation response can carry the inviter's id even though the invitee made the request. Use `::audit/profile-id` when the command has established the actor and the normal caller id is absent or not the right one. The RPC wrapper normalizes valid UUID strings and drops invalid overrides before falling back to the caller or zero. Hand-built events do not go through that normalization: pass a UUID to `audit/submit`. Reserve zero for cases without a known actor, such as SREPL actions.

`audit/submit` propagates failures. `wrap-audit` runs **after** the command's transaction, if it has one: an audit failure can fail the response even though the business mutation is already committed. An explicit `submit` inside the caller's transaction can roll back the mutation with the event. Low-level `audit/insert` only writes a full row when persistence is on; it does not fan out to webhooks or telemetry.

### Events used to rebuild state

Put the ids a projection needs in the event so the receiver does not have to join production tables. A create event needs the new entity's id. Supply `:team-id` when the command already has it; for writes the wrapper can resolve it from a project or file id, not a bare `:id`. Reads do not need that lookup. For a bulk operation whose internal calls do not emit, submit one create event per entity when full rows are persisted. Use `audit/submit-create-event` when you have the row or `audit/submit-file-created-event` when you have only a file id; the latter checks that the file exists.

- `add-team-member` reports an actual membership insert, not a conflict that made no change. The emitter submits in the caller's transaction when full rows are persisted. Keep the actor separate from `:member-id` when someone adds another person.
- `update-file` reports `"saved"`, `"partial"` or `"rejected"`. Only the first two write a change row and carry `:file-change-id`. The id points to the change, not the previous-state snapshot. Read it with `::db/remove-deleted false` because it is born with a future deletion date; even then, GC may already have removed it.
- For moves, preserve source and destination team ids. For cascades and hard deletes, report the affected ids at emission rather than expecting a projection to reconstruct them later from deleted rows. The `:delete-object` handler emits its cascade event in the same transaction; direct and snapshot cascades do not emit that event.
- `accept-organization-invitation` distinguishes direct and team invitations with `:organization-member-add-source` and `:belongs-to-team-on-add`. Keep that distinction in props; do not add another origin field.

### Browser events

The browser collector starts on either `:audit-log` or `:telemetry` from `get-environment-data`. If discovery fails it starts anyway; the backend still decides whether to accept events. The collector buffers a bounded number of events and sends batches for the current profile. It can lose events on shutdown, overflow or failure. HTTP success does not prove a write: if storage fails, the endpoint logs the error and still returns normally. If it does write both the full and shadow rows, they commit together.

Use a data-event or the `Event` protocol for a new event. Use kebab-case names (the public schema caps names at 50 characters) and reuse existing prop names. Do not add a browser copy of an action the backend already reports unless the browser observes something distinct. Honor `skip-audit?` when the UI resumes the same gesture. Performance events are usage data, not audit evidence.

## 3. Destinations and privacy

```text
RPC / management / public ingest
  ├─ full rows ────┐                       ┌─ Nexus archive
  ├─ shadow rows ──┴─ audit_log buffer ─────┼─ admin-console archive
  │                                        └─ telemetry endpoint
  ├─ webhook jobs (marked producer events)
  └─ error reporters (crash events from public ingest; also receive backend logs)
```

### Full rows and archive consumers

Nexus receives full events, not telemetry shadows. Penpot sends Transit with the instance's `public-uri` as `origin` and a Nexus shared key. In the Nexus archive contract, Penpot's `created-at` becomes `received-at`; Nexus has its own `created-at` and `tracked-at` stays the origin time. The default telemetry endpoint also points to Nexus, but it is a separate ingest path with a reduced payload.

The admin-console archive is **Penpot → Nitrate**, distinct from either Nitrate producer channel. Its consumer selects an allowlist of full events and enriches them with names or emails needed by the destination. The producer does not know that allowlist. Nonmatching rows still advance the cursor.

### Webhooks and error reporters

Webhooks use jobs, not a cursor over the audit buffer. A marked event schedules matching and delivery for the affected team's webhooks. When debounce is configured, a burst delivers the **last payload**, not a list of the events. Delivery failure changes webhook status and its delivery log, not the origin audit row. The audit `webhook` trigger records the match count, including zero, only if full-row persistence is on. Its `:event-id` comes from the producer event, so it can refer back to the full row when that row exists. A telemetry copy has its own id.

With `:error-reporting` on, public ingest forwards `unhandled-exception` and `exception-page` to the reporters even if neither kind of audit row is stored. The database reporter runs on that flag alone; Mattermost also needs its webhook URL. Reporters also receive backend error logs. The browser collector still has to run for browser crashes to reach them. Bound crash reports at the source: a message that embeds a serialized file can exceed the receiver's body limit and prevent later rows in the chunk from advancing. See `mem:frontend/routing-app-shell-subtleties` for frontend report limits and classification.

### Telemetry shadows

Telemetry writes a separate, reduced copy, even when full-row persistence is also on. It truncates times to the day, sets the stored IP to `0.0.0.0`, and filters props and context before storage. The telemetry POST omits row id, `created-at` and IP; it keeps `profile-id`, a stable UUID that can link events. Treat the copy as reduced data, not as proof of anonymity.

For example, auth events can keep a derived `email-domain`, not the raw email; a full archive row may still contain the email. Context drops request and session identifiers but keeps an allowlisted `:initiator` for counts by application. Event-specific filters allow some values the default filter would drop. Check `filter-telemetry-props` and `filter-telemetry-context` with their tests before changing a field that leaves the instance.

The telemetry consumer scans the same stream, skips full rows, and ships shadows independently of the full archives. It uses a three-hour pause and larger chunks to avoid flooding the endpoint with small requests. The legacy `:telemetry` job still sends the aggregate report and newsletter data; it does not drain these rows. Its event counters see only the shadows still in the buffer, not a complete day's activity.

## 4. How the buffer drains

`audit_log` is a buffer, not the archive. Producers insert rows without knowing the destinations. Each consumer keeps its own position in `audit_log_consumer_cursor`. Worker instances run consumers in background threads, not jobs. A session advisory lock allows only one pass per consumer per database. The pass holds a pool connection and fixes its upper bound (the *frontier*) at the start, so it ends even while new events arrive.

### Ordering and retry

The cursor orders rows by `(xid, seq)`: transaction id first, then sequence within the transaction. A snapshot cut based on `pg_snapshot_xmin` keeps the reader from moving past an earlier open transaction. For example, A inserts a row and stays open. B inserts and commits while A is open; then A inserts a second row and commits. A sequence-only cursor could read B and skip A's first, still invisible row. With the cut, the drain waits and can then deliver both A rows before B. This is neither global insertion order nor commit order.

The cut is conservative across the database. An unrelated long write transaction or a slow send inside a cursor transaction can delay what other passes can read, even though they do not lock one another's cursors. The GC can still delete up to positions already confirmed.

Each chunk sends and advances its cursor in one local transaction. A refusal aborts that chunk and leaves its cursor in place; earlier committed chunks stay done. An external send cannot roll back with the local transaction: if the destination accepts it and the cursor commit fails, the next pass can send it again. Consumers must handle repeats. A filtered chunk advances even if it sends nothing.

### Registration, deletion and replay

The component registers its cursor at `(0, 0)` after configuration succeeds and before its loop starts, so a new consumer replays what remains in the buffer. Consumer ids are stable and never reused: `0` telemetry, `1` Nexus, `2` admin console. A disabled consumer keeps its position until retired. Turning off both full-row consumers stops new full rows; it does not empty the buffer or stop telemetry shadows.

Only the GC deletes. For full rows it deletes through the oldest registered cursor other than `0`; for shadows it deletes through cursor `0`. A missing cursor leaves its cursor-based path idle. A separate seven-day retention path deletes shadows by age regardless of cursors. It can delete unsent shadows; an older shadow can still ship if the consumer reads it before GC removes it.

The GC reads cursors again for each batch but does not serialize deletion with registration. A new consumer can miss up to one batch **per concurrent GC execution that read the previous cursor state**. There is no database-wide one-batch limit: cron job exclusivity is scoped by tenant, while the buffer and cursors are shared by the database. For a complete replay of what remains, pause the GC and wait for queued and running GC jobs to finish before registering. Follow `operations/2.20.0-consumer-generalization.md` for the rollout and replay procedure.

### What to watch

- `penpot_audit_consumer_lag_events` counts visible rows after a consumer's cursor, capped at 10,000. It includes rows that the consumer will filter; it is not a count of undelivered requests.
- Pass outcomes distinguish a failed pass from `busy` (another instance holds the lock). Some `busy` is normal with several workers; sustained `busy` with no successful passes warrants checking the lock holder. `skipped` rows were filtered by the destination, not lost.
- `penpot_audit_consumer_discarded_total{reason="gc"}` counts all committed deletions. The `purged stale telemetry events` warning counts **age-based deletions**, not confirmed missed deliveries.

## 5. Configuration, changes and tests

| Setting | Effect |
|---|---|
| `:nexus`, `:admin-console` | Start the corresponding archive consumers and enable full-row persistence. Set the intended flags consistently on web and worker instances: each computes persistence at boot. Nexus needs `:nexus-uri`. |
| `:telemetry` | Collect and ship shadow rows to `:telemetry-uri`, separately from full-row persistence. |
| `:error-reporting` | Start error reporters and enable public ingest for crash reports without requiring stored audit rows. |
| `:audit-log-gc` | Schedule the GC, on by default. Use `disable-audit-log-gc` during a mixed-version rollout and follow the runbook before restoring it. |
| `:audit-log-logger` | Log backend audit events, including event data. This is not the archive or a substitute for persistence. |

`:audit-log` is not a configurable flag. As a wire signal it means full-row persistence **or** error reporting. `:telemetry` is a separate wire signal. Public ingest skips processing on a read-only pool or when none of those destinations is enabled.

To add a consumer, give it a non-reused id and a transport definition for the shared `run-pass` loop. Configure its `:process`, optional `:prepare` and worker component; do not add producer-specific fan-out or seed its cursor in a migration. For contract tests, drive the real pass with prepared cfg; see `mem:backend/testing` for the test system and stubs. When changing an emitter, assert actor, props and exact emission counts for the specific backend action. Checking only the first matching event misses duplicates. Do not assert exact global counts from best-effort browser collection.

### Where to look

- Producer and telemetry filtering: `backend/src/app/loggers/audit.clj`; RPC wrapper: `backend/src/app/rpc.clj`.
- Public ingest and discovery: `backend/src/app/rpc/commands/audit.clj`; browser collector: `frontend/src/app/main/data/event.cljs`; trusted ingest: `backend/src/app/rpc/management/nitrate.clj`.
- Shared drain, transports and GC: `backend/src/app/loggers/audit/`; rollout: `operations/2.20.0-consumer-generalization.md`.
- Contract examples in `backend/test/backend_tests/`: `rpc_audit_test.clj` for emitters and ingest, `loggers_audit_consumers_test.clj` for cursor ordering, retry and GC, and `rpc_team_test.clj` for invitation attribution.
