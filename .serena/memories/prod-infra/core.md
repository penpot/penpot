# Production infrastructure

- Start at `app.config` for backend service settings and `docker/images/` for deployment defaults. Object storage lifecycle and bucket rules: `mem:backend/storage`. Local service isolation: `mem:devenv/core`. Metrics and read-replica limits: `mem:prod-infra/grafana`.

## Worker topology and draining

- PostgreSQL is authoritative for job state and claims. The dispatcher and runners must use the same tenant and Redis/Valkey destination to share a queue; sharing PostgreSQL alone is insufficient. Pub/Sub is separate from the queue: notifications go to the executing backend's configured bus and tenant. Keep heavy job queues separate from queues serving unrelated work.
- `scheduled_task` locking serializes competing cron ticks while the transaction is open; `skip-if-active` rejects duplicate live jobs. Neither promises one execution across all time: a later tick can submit after the prior job becomes terminal, and work can outlive an aborted row. Enforce idempotency or ownership on the data the handler touches. For dedupe, recovery and metric semantics see `mem:backend/jobs`.
- Use `app.srepl.main/set-offline` to drain one instance. `/readyz` stops routing traffic to it without querying PostgreSQL; in-flight requests can finish. Restart brings it online. Draining ingress does not migrate job execution: unclaimed hand-offs and expired running leases are handled by the job abort sweeps. Read-only backends do not start workers.
- Redis timeouts alone do not prove Redis is stalled; a paused or starved JVM can fail to read replies. Check client/process health as well as the service before assigning cause.

## Production SQL and migrations

- Use plain SQL for the deployed toolbox, not local `scripts/psql` or psql meta-commands. Start with known IDs and use point lookups on indexed reference columns; split lookups rather than wrapping several indexed columns in an expression that can force a scan. The read replica can cut long queries.
- The migration runner groups pending steps in one transaction; it cannot run `CREATE INDEX CONCURRENTLY`. A plain index build blocks writes, and even skipped DDL can acquire table locks. For large tables plan and verify the out-of-band work before deployment; never mark a migration as applied unless the deployment procedure accounts for all work it would skip.
- `IF NOT EXISTS` matches a name, not the definition, validity or completeness. An interrupted concurrent build can leave an invalid index that satisfies it, silently defeating deduplication or exclusivity. Verify `pg_index.indisvalid` and the index definition before relying on it. On partitioned tables build child indexes concurrently, then attach all of them to the parent index.
- For a reference whose violation a GC task must catch and retry, use `DEFERRABLE INITIALLY IMMEDIATE`: a deferred check can fail at commit outside the catch. Adding a validated foreign key can scan the whole referencing table; check the supported PostgreSQL version before assuming `NOT VALID` works for partitioned tables.
- Stop old readers and writers before removing a reference they still use. Otherwise a GC can delete a live object and concurrent writes can escape the backfill. Write the replacement reference and clear the old one atomically. For audit consumers and retention during mixed-version rollout, see `mem:audit-log`; disabling a consumer does not automatically disable GC.
