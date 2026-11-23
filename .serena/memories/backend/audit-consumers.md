# Audit consumers and retention

- `audit_log` is a buffer, not the archive. Start at `app.loggers.audit.consumers/run-pass` and `app.loggers.audit.gc` for draining and deletion. Transport definitions live under `app.loggers.audit.consumers`; producer and privacy rules: `mem:audit-log`.

## Ordering and delivery

- Consumers run on worker instances in threads, not jobs. A session advisory lock allows one pass per consumer per database. The pass holds a pool connection and fixes its frontier so continuous inserts cannot extend it forever.
- The cursor orders by `(xid, seq)`, not insertion order or commit order. The snapshot frontier prevents a pass from moving past an earlier open transaction that still hides rows. An unrelated long write can delay progress; this is not necessarily a cursor lock conflict.
- A chunk sends and advances its cursor in one local transaction. Refusal leaves that chunk's cursor unchanged and earlier committed chunks intact. A destination can accept a send before the cursor commit fails; consumers must tolerate repeats. Filtered rows advance the cursor even when nothing is sent.
- Nexus and admin-console archives consume full rows; the latter filters and enriches at delivery, not at the producer. Nexus's `received-at` is Penpot's server capture time, not its own archive creation time. Telemetry drains only shadows; the legacy aggregate telemetry job does not drain them and cannot count shadows already removed from the buffer.

## Retention and replay

- Register a new consumer only after transport preparation succeeds, before starting the loop. It starts before the first row and replays what remains; never reuse a consumer ID. Disabling a consumer leaves its cursor and does not disable GC. Full-row persistence and worker flags must agree across web and worker instances.
- Full rows are collectible through the oldest registered full-row cursor. Shadows use the telemetry cursor and also age out independently, even if unsent. No cursor means no cursor-based deletion for that path, not a suspension of age-based retention.
- GC re-reads cursors before each batch but does not serialize deletion with registration. A new consumer can miss one batch **per concurrent GC run** that read the old cursor state; job exclusivity is tenant-scoped but the buffer is shared. For complete replay of the remaining buffer, pause GC and wait for queued and running GC jobs to finish before registering.
- Keep mixed-version cleanup disabled while old and new consumers can still act independently. A consumer being disabled is not proof that its rows are safe to delete.
- Lag includes rows that will be filtered and has a cap; it is not a count of undelivered sends. `busy` means another instance holds the pass lock. Age-based shadow purges do not prove missed deliveries. See the metric definitions in the consumer and GC code.
- Test with `backend-tests.loggers-audit-consumers-test` and the real `run-pass` on prepared cfg; see `mem:backend/testing`. Do not seed cursors in a migration or add producer-specific delivery loops.
