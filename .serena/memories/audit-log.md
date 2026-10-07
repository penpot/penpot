# Audit Log and event processing

Complete guide to the Penpot event system: what gets recorded, where each piece lives, and how events travel from the moment they happen until they are archived. Start with the overview (sections 1-3); the deeper technical detail comes later (section 8 on). If you only want the idea, the first three sections are enough.

> Note about this memory: unlike other Serena memories (dense notes written for LLM agents, see `mem:memory-maintenance`), this document is also written for human developers who need full context. That is why it uses plain language and examples. It replaces the old `mem:backend/audit-log`, which is now just a redirect stub.

## 1. What it is (2-minute overview)

The *audit log* answers "who did what, when, and from where". Every important user action becomes an **event**: a small object with a name, a type, who did it, from which IP, plus action data (`props`) and metadata (`context`).

The system has:

- **Three producers** (they create events): the Penpot backend (RPC layer), the Penpot frontend (browser app), and the Nitrate admin-console, which sends both server-side and client-side events.
- **One central buffer**: the Postgres `audit_log` table in Penpot. It is temporary: it holds events for minutes/hours until they are archived, then they are deleted.
- **Four consumers** (they read events): webhooks, error reporters, telemetry, and the external archive in Nexus.
- **One long-term archive**: **Nexus**, a separate service that stores events for the long term. Archiving to **Nitrate** is in development and **does not exist on the current branch** (see section 11).

```
Penpot frontend ─ POST push-audit-events (public) ─────────▶┐
Nitrate admin-console (browser) ─ POST push-audit-events ──▶│
Nitrate admin-console (server) ─ POST push-audit-events  ──▶│ ─▶ audit_log table (buffer) ── cron every 5m ──▶ Nexus (archive)
Penpot backend (RPC wrap-audit) ─ submit ───────────────────┘         │   ├──▶ webhooks
                                                                      │   ├──▶ error reporters
                                                                      └──▶ telemetry (shadow rows)
```

The Penpot backend is the **center**: it checks, completes, and stores every event, no matter where it comes from. Nobody writes to the database directly; everything goes through the backend.

This document is the reference for all flows. One thing to keep in mind while reading: the Nitrate admin-console (both its server and its browser UI) is a **private** application, not a public client. Its events describe private SaaS management actions (organizations, subscriptions, SSO), and its server channel uses a trusted shared key instead of a user session.

## 2. The three kinds of events

Not all events are worth the same. This is the most important distinction in this document:

| Aspect | Backend events | Penpot frontend events | Nitrate admin-console events |
|---|---|---|---|
| What they describe | Real actions run on the server (create file, login, invite to team...) | Things that happen in the Penpot browser app (navigate, edit, JS errors, performance) | Organization and subscription actions in the admin-console (create/rename/delete organization, members, SSO settings, checkout, onboarding) |
| Trust level | **High**: created inside the same request that runs the action | **Best-effort**: some loss is expected (tab closed, offline, full buffer); they show **usage**, they are not strict proof | **Mixed**: server-side events are high trust (like backend); browser events are best-effort (like frontend) |
| Who sets `profile-id` | The server, from the logged-in session (never trusts the client) | The server too: it ignores what the client says and uses the session | Server channel: the trusted admin-console server sends it explicitly. Browser channel: the Penpot server uses the session |
| Who sets the time | Server time (`created-at`); the source of truth | Client proposes `tracked-at`, but the server fixes it if it is in the future or more than 1h late | Same rule: server stamps `created-at`; client `tracked-at` is corrected |
| Used for | Audit, webhooks, compliance, abuse checks | Product analytics, usage counters, crash reports | Organization audit trail (admin-console e2e spec), subscription metrics |

Rule of thumb: if you need to know for sure "did this user do X?", look at `source = backend`. If you want to know "how do people use the app?", also look at `source = frontend`.

## 3. The `audit_log` table: a buffer, not a store

The Penpot `audit_log` table **is not the final destination**. It is a waiting tray: events sit here until the archive cron sends them to Nexus and the cleanup cron (GC) deletes them.

Main columns (migration `backend/src/app/migrations/sql/0054-add-audit-log-table.sql` and later ones):

- `id` uuid PK (`gen_random_uuid()` today; the PK used to be `(created_at, profile_id)`).
- `name` / `type` text NOT NULL (for example `name = "create-file"`, `type = "action"`).
- `created_at` timestamptz NOT NULL default `now()`: **server time**, the source of truth.
- `tracked_at` timestamptz default `now()`: what the client claimed; fixed on ingest.
- `profile_id` uuid NOT NULL: who did it.
- `source` text: `backend` / `frontend` for full rows, `telemetry:backend` / `telemetry:frontend` for anonymized copies (see section 10).
- `ip_addr` inet: real IP (or `0.0.0.0` on telemetry rows).
- `props` / `context` jsonb holding transit-encoded maps: action data and metadata (versions, browser, request-id...).
- `archived_at` timestamptz: NULL until Nexus confirms receipt; only then the row can be deleted.

Indexes (each consumer has its own, so a slow consumer never blocks the others):

- Partial `created_at WHERE archived_at IS NULL`: used by the archive scan.
- Partial `archived_at WHERE archived_at IS NOT NULL`: used by the GC.
- `(source, created_at)`: used by the telemetry scan.

Life of a row: `INSERT` → (every 5 min) archive sets `archived_at = now()` **only if Nexus answers 204**, in the same transaction → (every 5 min) GC runs `DELETE WHERE archived_at IS NOT NULL` **with no age filter**. So archive must run before GC, or data is deleted without being sent.

## 4. Anatomy of an event

Every event ends up with this shape (schema `schema:event` in `backend/src/app/loggers/audit.clj`):

- `name`: what happened (`"create-file"`, `"navigate"`, `"create-organization"`...). For backend, it defaults to the RPC command name (prefixed with `<module>-` outside `main`).
- `type`: category (`"action"`, `"identify"`, `"trigger"`). Most are `"action"`.
- `profile-id`: **who** did it (uuid). See the invariant in section 5.
- `ip-addr`: request IP (or `"0.0.0.0"` on telemetry).
- `props`: action data (team/project/file ids, visited route...). **Never secrets** (see `clean-props`).
- `context`: metadata about how/when it was captured (backend/frontend version, user-agent, locale, browser, `request-id`, `initiator`, `event-origin`...). See section 8 for the full field guide, including `initiator` (which app started it: `"app"` means Penpot itself).
- `source`: `backend`, `frontend`, `telemetry:backend`, `telemetry:frontend`. Nitrate admin-console server events land as `backend`; its browser events land as `frontend` (see section 7).
- `source`: `backend`, `frontend`, `telemetry:backend`, `telemetry:frontend`.
- `tracked-at` / `created-at`: claimed time and server time.
- Memory-only (never stored): `::webhooks/event?`, `::webhooks/batch-key`, `::webhooks/batch-timeout` for the webhook fan-out.

Backend `create-file` example (simplified):

```clojure
{:name "create-file" :type "action" :source "backend"
 :profile-id #uuid "…" :ip-addr "203.0.113.7"
 :props {:project-id #uuid "…" :name "My design"}
 :context {:version "2.x" :initiator "app" :request-id "…"
           :client-version "2.x" :client-user-agent "Mozilla/…"}
 :created-at #inst "…" :tracked-at #inst "…"}
```

Frontend `navigate` example:

```clojure
{:name "navigate" :type "action" :source "frontend"
 :profile-id #uuid "…" :ip-addr "203.0.113.7"
 :props {:route "dashboard" :team-id #uuid "…"}
 :context {:version "2.x" :locale "en" :browser "Chrome" :os "Linux"
           :event-origin "dashboard" :session-id "…" :session #inst "…"}
 :created-at #inst "…" :tracked-at #inst "…"}
```

## 5. Backend events: the happy path

Most backend events need **no manual code**. The `wrap-audit` wrapper in `backend/src/app/rpc.clj` runs after every RPC command when `:webhooks`, `:audit-log`, or `:telemetry` is on (unless the command sets `::audit/skip`). It builds the event with `prepare-rpc-event` and passes it to `audit/submit`.

Each command can customize through result metadata (`rph/with-meta`):

- `::audit/replace-props`: replaces props fully (auth commands use `profile->props` so a register event carries the profile, not the password).
- `::audit/props`: merges extras over the request params.
- `::audit/context`, `::audit/profile-id`, `::audit/name`, `::audit/type`: override the defaults.
- `clean-props` always strips nils, namespaced keys, and `:session-id/:password/:old-password/:token/:client-secret` as a last line of defense. **Never put secrets in props.**

Critical `profile-id` INVARIANT: the event belongs to **whoever made the request**. It resolves as `::audit/profile-id` metadata → `::rpc/profile-id` → `uuid/zero`. It is **never** taken from the response: some results carry someone else's `:profile-id` (for example `get-error-report` returns the report with its content merged; `verify-token` on an invitation returns the inviter), and using it would blame the wrong person. Commands with `::rpc/auth false` (`login-*`, `register-profile`, `create-demo-profile`, `verify-token`, `prepare-register-profile`) have no caller to fall back on, so they **must** set `::audit/profile-id` in the metadata or the event lands on `uuid/zero`.

The `::audit/profile-id` override goes through `coerce-profile-id`: a string is parsed, anything that cannot become a uuid is dropped (with a warning) and the event falls back to the caller. Do not pass the value raw: `schema:event` needs a uuid and `submit*` swallows the validation error (it only logs, the RPC still succeeds), so a non-uuid override **loses the row silently**. Hand-built events with `event-from-rpc-params` + `submit` (clone template, accept team invitation, `management/nitrate` push-audit-events) do **not** go through that coercion: they must already carry a uuid.

Entry points:

- `submit`: the normal one (fills defaults, validates, runs inside `tx-run!`, never breaks the RPC).
- `insert`: low level, for CLI helpers and the webhook subsystem; direct write, no webhook/telemetry fan-out, silent unless `:audit-log` is on.
- Boot emits `trigger/instance-start` from `setup/props` so every restart stays visible.

Cases already covered by tests, do not break: `accept-organization-invitation` tells its two flows apart **with props, not with origin**: `:organization-member-add-source` is `"direct-organization-invitation"` or `"team-invitation"`, and `:belongs-to-team-on-add` repeats it as a boolean. Do not add a third prop. The `context` `:event-origin` belongs to the browser (`make-data-event` sets it and it is on the frontend allowlist; `safe-backend-context-keys` has no `:event-origin`, so a backend event has nowhere to put one).

## 6. Penpot frontend events: what is collected, with what guarantees

The browser **never writes to the table**; it POSTs transit batches to `push-audit-events` (`backend/src/app/rpc/commands/audit.clj`), which stamps server `id`, session `profile-id`, request IP, server `created-at`, and server-side `:initiator` (from the `x-client` header, see section 8; anything the client sends as `initiator` is overwritten), and distrusts the client clock (future or >1h-late `tracked-at` is reset, the original kept in `context.original-tracked-at`). Without `:audit-log`/`:telemetry`, or on a read-only pool, the endpoint is a no-op.

Valid types (`schema:frontend-event`): `"action"`, `"identify"`, `"trigger"`. Names must match `#"[\d\w-]{1,50}"` (max 250). `props` is a free map with no schema: **tests are the only guard of the contract** (see section 13).

What they are for: usage metrics (navigation, editing), workspace file stats, Nitrate membership changes, crash reports (`unhandled-exception` / `exception-page`). **Not proof for audit**: collection is best-effort and includes `PerformanceObserver` noise (`performance-*`, `user-input`, `long-task`). So backend tests must never assert exact frontend event counts.

How the browser collects (`frontend/src/app/main/data/event.cljs`), most to least important:

1. **On-demand start**: `initialize` asks the backend with `get-environment-data` (old `get-enabled-flags` is deprecated) and only starts on `:audit-log` or `:telemetry`. On RPC failure it still starts in telemetry mode (the backend will reject if needed).
2. **Potok translation**: `make-proto-event` (events with the `Event` protocol) and `make-data-event` (data-events with `::name`) turn the action into `{type, name, props, context}`. `simplify-props` flattens complex values (maps → `:placeholder/map`, and so on) and drops namespaced keys and nils. Context gains `:event-origin`, `:event-namespace`, `:event-symbol`.
3. **Capped buffer**: queue of max 1024 events (`append-to-buffer` drops beyond that); every 2s debounce (or on logout, or `::force-persist`) a chunk of max 100 is taken, filtered to the current profile, and sent fire-and-forget to `api/main/methods/push-audit-events` as transit. On confirm, the chunk leaves the buffer (`::chunk-persisted`).
4. **Performance noise**: `PerformanceObserver` (`event`/`longtask`) plus blocking measurement with `scheduler.postTask` create `trigger` events (`performance-blocking-event`, `user-input`, `long-task`) with 1s debounce and 1s threshold. Info only, not audit.
5. **`skip-audit?`**: UI flag (for example resuming a pending dashboard action after Nitrate SSO, or moving a team into an organization) so one user gesture is not counted twice.

## 7. Nitrate admin-console events: the third producer

The admin-console is the third producer. It sends organization and subscription events through **two channels** that look the same once stored, but have different trust levels. All the sending code (server and browser helpers) lives in the private Nitrate repo: it is not Penpot code and no file paths are cited here. What this section documents is only the Penpot-visible side: endpoints, payloads, and which event names arrive.

### 7a. Server-side channel (high trust, like backend events)

Code: the admin-console server has a helper that POSTs to the Penpot **management** API:

```
POST api/management/methods/push-audit-events
headers: x-shared-key: "admin-console <key>", Cookie: <user cookies>
body: {events: [{name, props, profileId, context?}]}
```

Auth happens at the HTTP layer (`backend/src/app/http/middleware.clj` `wrap-shared-key-auth`): the `x-shared-key` header carries `"<key-id> <secret>"` (`admin-console` here), checked in constant time against the configured keys; the key-id lands in the event context as `:initiator`. The call goes server-to-server (ky client, internal URI, user cookies forwarded).

Who uses it:

- Organization management (best-effort wrapper): `create-organization`, `rename-organization`, `delete-organization`, member add/remove, and more. It optionally passes the UI origin in the context.
- Subscriptions: `create-subscription`, renewals, cancellations, and more, including deployment lookup (saas/selfhost).

Failure policy: every send is best-effort. A failure never rolls back the business action; it is only recorded for debugging.

Backend side (`backend/src/app/rpc/management/nitrate.clj` `::push-audit-events`): `::audit/skip`, `::rpc/auth false` (trust comes from the shared key, not from a user session). It builds context with `audit/prepare-context-from-request` + `request-id`, takes the given `profile-id` as-is (no coercion here, must already be a uuid), sets `tracked-at = request-at`, defaults `type` to `"action"`, and calls `audit/submit` (which defaults `source` to `"backend"`). So these rows land as **`source = backend`**: they are audited like backend events because a trusted service set the author.

### 7b. Browser channel (best-effort, like frontend events)

Code: the admin-console browser has a helper that POSTs to the Penpot **public** API:

```
POST api/main/methods/push-audit-events
credentials: include, keepalive: true
headers: x-client: "penpot-admin-console/<version>"
body: {events: [{name, type: "action", timestamp, props, context: {eventOrigin?}}]}
```

Dozens of helpers cover the admin-console UI: `create-organization`, `delete-organization-member`, `create-subscription` / `create-trial-subscription`, `open-subscription-modal`, `start-nitrate-checkout`, `open-current-subscription`, SSO settings flows (`open/apply/success/fail/discard-organization-sso-changes`, activate/deactivate modals), `change-organization-advanced-permission`, `onboarding-step` / `onboarding-finish`, `close-subscription-modal`. These helpers live in the private Nitrate repo: they are not Penpot code, you will not find them in this repository. Only the event names above (what arrives at Penpot) are listed here on purpose.

These rows land as **`source = frontend`** with the session `profile-id`: same guarantees as Penpot frontend events (best-effort, usage analytics). Every browser helper sends an `eventOrigin` in the context: plain `"admin-console"`, with a suffix only when the same event is emitted from two places in the app (for example the SSO discard event distinguishes the config form from the unsaved-changes modal).

The Nitrate repo also has a shared, generic push client for the management endpoint (not tied to the admin-console server): any other Nitrate-side service that needs to record something in the Penpot audit log can reuse it instead of writing its own HTTP call. Same endpoint, same payload shape as the server channel above.

## 8. How the frontend helps the backend audit: HTTP headers

Besides explicit events, **every RPC request from the frontend carries headers** so the backend can fill its own event `context`. This is how the backend learns "which browser/version/screen" made the request:

- `x-frontend-version` and `x-client: penpot-frontend/<version>`: added by `default-headers` in `frontend/src/app/util/http.cljs` to **all** fetch requests (unless `omit-default-headers`). The admin-console browser channel sends `penpot-admin-console/<version>` instead (`x-frontend-version` is ignored on the event-ingest path, only `x-client` matters there).
- `user-agent`: set by the browser alone; the backend trims it to 500 chars (`get-client-user-agent`).
- `x-external-session-id`: external SaaS session via `cf/external-session-id`; added by `repo.cljs` (`send!`, special `cmd!`, `multipart-upload`, export...) and also stored by `persist-events` in the frontend event context. The backend checks it (max 256, ignores `"null"`/empty).
- `x-session-id`: `cf/session-id` (tab session), only in the main `send!`.
- `x-event-origin`: UI origin (`::ev/origin` from param metadata, for example `"dashboard"`, `"workspace"`); sent by `send!` and the OIDC/export/upload `cmd!`. The backend trims it to 200 chars and stores it as `:client-event-origin` (different from the `:event-origin` the frontend puts in its own context!).

On the backend, `prepare-context-from-request` (`backend/src/app/loggers/audit.clj`) joins everything into the event context: `:external-session-id`, `:initiator` (`auth-key-id` or `"app"`), `:access-token-id/type`, `:client-event-origin`, `:client-user-agent`, `:client-version`, `:version` (backend), plus `:request-id`. Only `safe-backend-context-keys` (`:version`, `:initiator`, `:client-version`, `:client-user-agent`) survive in the telemetry shadow copy; the rest (`:client-event-origin`, `:external-session-id`, `:access-token-id/type`, `:request-id`...) is stored fully in the normal row and sent to Nexus, but dropped from telemetry.

### Context field guide: `initiator`

`initiator` labels the application that started a request. The server sets it on both channels, but only the shared-key channel authenticates the sending application:

- **Backend channel** (RPC + management API): it comes from the shared-key auth layer (`wrap-shared-key-auth` in `backend/src/app/http/middleware.clj`): when a trusted service calls the management API with `x-shared-key: "<key-id> <secret>"`, the key-id (lowercased) is stored on the request as `::http/auth-key-id`, and `prepare-context-from-request` copies it into `:initiator`. When there is no shared key (a normal user RPC), it falls back to `"app"`, which means Penpot itself.
- **Frontend channel** (`push-audit-events`): `get-client-initiator` maps the `x-client` header product to an initiator: `penpot-frontend` → `"app"`, `penpot-admin-console` → `"admin-console"`, legacy `penpot-nitrate` (old admin-console releases, remove once redeployed) → `"admin-console"`, missing or unknown → `"app"`. It overwrites any `initiator` in the event context, but the caller controls the header. Any authenticated caller can claim `"admin-console"` by sending that product in `x-client`. Use this label for usage analytics; it does not prove which application sent the event or grant permissions. The session still determines `profile-id`.

Values you will see:

- `"app"`: normal Penpot traffic (browser → public RPC or event ingest, or backend internal work). This is the vast majority.
- `"admin-console"`: the authenticated Nitrate admin-console on management API calls (section 7a), or a caller claiming that product on browser events (section 7b, via the `x-client` mapping).
- `"nexus"`, `"exporter"`, `"media-processor"`: other first-party services using their own shared keys (key ids come from `::setup/shared-keys` in `backend/src/app/setup.clj`, derived from the instance secret unless overridden).

Why it matters: it supports per-application usage counts, with the trust level described above. It survives into telemetry on both channels (one of the four backend keys, and on the frontend allowlist too), so per-initiator counts stay available even on anonymized rows.

More metadata the Penpot frontend adds inside each event (not as headers): `collect-context` with `ua-parser` (browser, engine, OS, device, screen, CPU arch), `locale` (updated on language change), `:session` / `:session-id` / `:external-session-id`, and SaaS host extras (`add-external-context-info`).

## 9. Consumers I: webhooks

Webhooks let an external URL get notified when something happens in a team (for example "a file was created"). The flow has three steps:

1. **Match.** Some audit events are marked as webhook-relevant when they are created. For each of those, Penpot looks up the active webhooks of the affected team: it reads the team straight from the event, or finds it through the project or the file in the event. Each matching webhook gets its own delivery job. If several quick events belong together, they are grouped into one delivery (the grouping rule travels with the event itself).
2. **Deliver.** Penpot POSTs the event to the webhook URL in the format that webhook asked for: JSON (with camelCase keys), transit, or form-encoded. Every attempt is written to a delivery log with the request, the response, and the error if any, so failures can be inspected later.
3. **Protect.** Each webhook counts its consecutive failures. After 3 in a row, Penpot switches the webhook off automatically, so a dead URL stops receiving traffic until someone fixes it. A successful delivery resets the counter to zero.

One guarantee: sending webhooks never changes the audit event itself. If delivery fails, only the webhook (and its delivery log) is affected; the audit row stays untouched and still gets archived normally.

## 10. Consumers II: error reporters

Both `app.loggers.database` and `app.loggers.mattermost` listen to backend `:error` logs **and** frontend crash events (found by the `::audit/event` marker), through a sliding-buffer channel so a flood cannot stall the app. The database one stores them in `server-error-report` (source 3 = backend log, 4 = audit-log origin, 5 = rate-limit); the Mattermost one forwards a short note to `:error-report-webhook` when set.

Both reporters only start when the `:error-reporting` flag is on (off by default). The database reporter starts on the flag alone; the Mattermost reporter needs the flag **plus** its webhook URL configured. Without the flag, nothing is stored and `emit` calls from `push-audit-events` are silent no-ops. Each reporter also keeps a runtime on/off switch for emergencies via REPL.

Result: crash reports only exist if the frontend collector runs and `push-audit-events` accepts `unhandled-exception`/`exception-page`. Turning off the whole pipeline also blinds frontend error reporting.

## 11. Consumers III: telemetry (shadow rows)

Telemetry reuses the same table with **anonymized shadow rows** (`source LIKE 'telemetry:%'`): day-truncated times, `0.0.0.0` IPs, props reduced to uuid/boolean/number values plus a few allowed fields (`lang`, `auth-backend`, derived `email-domain` — never raw emails), and a minimal context allowlist. Full + shadow rows can exist together per event when both flags are on; that duplication is on purpose.

Filters (`filter-telemetry-props` / `filter-telemetry-context`): by default only uuid/boolean/number with a simple key; exceptions: login/register/update-profile and frontend `identify` keep `lang`/`auth-backend`/`email-domain`; `organization-sso-auth-failed` keeps `failure-reason` if it is a known one; `instance-start` passes whole; frontend `navigate` keeps `[:route :file-id :team-id :page-id]`. Free text drops from telemetry but **stays in the full row and in Nexus**.

Shipping (`backend/src/app/tasks/telemetry.clj`): the cron sends shadows to `:telemetry-uri` as JSON in 10k batches and **deletes** them on success; it purges leftovers older than 7 days. It also sends a legacy report (counters, teams, email domains...). On official hosts nothing is collected or sent (`telemetry-excluded?` covers `penpot.app`/`penpot.dev`).

### Where telemetry events go: `telemetry.penpot.app` is Nexus

The default `:telemetry-uri` (`https://telemetry.penpot.app/`) points at the **Nexus telemetry endpoint**, so telemetry is a second road into Nexus, separate from the archive cron. The payload is plain JSON:

```
POST https://telemetry.penpot.app/
{type: "telemetry-events", version, instance-id, events: [...]}
```

Nexus dispatches on `:type`: `"telemetry-events"` batches are coerced and inserted into its own `audit_log`; anything else (old flat payloads, `"telemetry-legacy-report"`) is upserted into `telemetry_report` per instance + day. Three details matter:

- **Anonymization happens in Penpot, before sending.** By the time events leave, times are day-truncated, IPs are already `0.0.0.0`, props/context are filtered (see above), and emails are gone (only derived `email-domain` survives). Nexus additionally forces the outcome: it prefixes any non-`telemetry:` source, sets `received-at = tracked-at` (already truncated), pins IP to `0.0.0.0`, and stamps the sending `instance-id` into every event context.
- **No personal data is expected.** Profile ids are random UUIDs with no PII, so the telemetry consumer needs no login guard; grouping is by `instance`, not by person (see posthog-telemetry below).
- **The endpoint always answers 200.** Failures are swallowed and only counted (`nexus_telemetry_errors_total`), so a bad batch never breaks the sending instance; successes are counted (`nexus_telemetry_events_total`, `nexus_telemetry_reports_total`). Old instances may send a fressian+zstd base64 blob instead of a plain vector; Nexus decodes both (50 MiB cap against decompression bombs).

## 12. Archive to Nexus (today) and to Nitrate (future)

### Nexus: how it works today

Nexus is the long-term store. Every 5 min the `:audit-log-archive` cron takes 128-row chunks of unarchived rows (`FOR UPDATE SKIP LOCKED`, ordered by `created_at`), POSTs them as transit `{:events [...]}` with `x-shared-key: "nexus <key>"` (`:nexus-shared-key`, or derived from the instance secret) and `origin` = public-uri, and marks `archived_at = now()` **only on HTTP 204**, in the same transaction. Anything else is retried next run; flag on but no URI raises `:task-not-configured`. It loops with 100 ms pauses until empty.

On the other side, Nexus checks `origin` against its allowlist (403 if not allowed), coerces the events, and inserts them into its own `audit_log` (partitioned by `received_at`, with an incrementing sequence number and `source`). Watch the rename: Penpot's `created-at` is stored as **`received-at`** in Nexus; Nexus's `created-at` is when it was created there. `tracked-at` keeps the origin time. Telemetry batches arriving at `telemetry.penpot.app` land in the same table through the telemetry endpoint above.

Concurrent runs are safe: rows are locked with `FOR UPDATE SKIP LOCKED` inside the same transaction that sends and marks them, so two archivers running at once each take different rows. Only rows Nexus acknowledges (HTTP 204) get marked; anything else is retried on the next run.

### How Nexus consumes the data (and forwards to PostHog)

Stored rows are not the end: a consumer pipeline reads them back in sequence order and processes them. Each consumer remembers its place (sequence number), fetches the next chunk, runs its handler, then saves the new position. Three consumers run in order:

1. **`local`**: keeps a mirror of user profiles. It listens to profile events (`register-profile`, `update-profile`, `login-with-*`, `delete-profile`, ...) and upserts email, name, props, and delete markers. It runs first because the next consumer needs profile data.
2. **`posthog`** (runs after `local`): forwards our own SaaS archived events to PostHog. It sends an allowlist of backend track events (teams, members, projects, files, comments, Nitrate/SSO, ...) plus frontend events minus a noise exclusion list, grouped by **team**, with person identify data from the local profile mirror and snake_case props. Both paths include the `initiator` property when the stored context has one, so PostHog can split Penpot vs admin-console traffic.
3. **`posthog-telemetry`**: forwards events with `source = telemetry:frontend|telemetry:backend` to a separate PostHog project. Because these rows are already anonymous (no IPs, truncated times, filtered props), grouping is by **instance** (the self-hosted server, from `context.instance-id`), with one `$groupidentify` event per instance per chunk so PostHog learns per-instance properties (version, URI, flags). Each consumer has its own PostHog credentials.

So the full journey is: Penpot buffer → Nexus `audit_log` (via archive cron or telemetry endpoint) → consumers → PostHog (SaaS events grouped by team, telemetry grouped by instance) plus local analytics views and materialized KPIs.

### Nitrate: what exists and what is planned

Today **only Nexus archives**. Archiving Penpot → Nitrate is in development and not on this branch.

What already exists (do not confuse with archiving):

- **Nitrate → Penpot**: `POST /api/management/methods/push-audit-events` (`backend/src/app/rpc/management/nitrate.clj`) lets the trusted Nitrate backend inject events into the Penpot audit log (no RPC auth, `::audit/skip`, request context + `submit`). Section 7a is the main user.
- **Admin-console browser → collector**: usage events from the admin-console UI traveling through the normal public `push-audit-events` (section 7b).
- **Penpot frontend Nitrate bits**: membership/subscription events (`frontend/src/app/main/data/nitrate.cljs`, `nitrate-audit/*`) through the same public endpoint.

The future Penpot → Nitrate archive will follow the Nexus pattern (cron reading the buffer and POSTing batches), but there is no contract or flag on this branch yet. When it lands, document here: endpoint, auth, format, which `source` values travel, and how it shares the GC.

## 13. Flags and config

Flags (`common/src/app/common/flags.cljc`, varia; enabled with `PENPOT_FLAGS=enable-<name>`):

| Flag | What it does |
|---|---|
| `:audit-log` | Stores full rows; turns on `wrap-audit` and frontend ingest |
| `:audit-log-archive` | Turns on the cron that sends to Nexus (needs `:audit-log-archive-uri`) |
| `:audit-log-gc` | Turns on the cron that deletes archived rows |
| `:audit-log-logger` | Structured `app.audit` log per event |
| `:error-reporting` | Starts the error reporters (off by default); database reporter on flag alone, Mattermost on flag plus webhook URL |
| `:telemetry` | Stores shadow rows and turns on shipping (auto with `:telemetry-enabled`) |

Config (`backend/src/app/config.clj`): `:audit-log-archive-uri`, `:telemetry-uri` (default `https://telemetry.penpot.app/`), `:nexus-shared-key` / instance secret, `:public-uri` (sent as `origin` to Nexus).

## 14. Practical guide: add or change events

- **New backend command that must (or must not) emit**: `wrap-audit` does it alone; to exclude it set `::audit/skip`. To customize props/name/type use result metadata. Add a test mocking `app.loggers.audit/submit` (nil; `helpers.clj` stubs it globally) and assert `:call-args-list` — see `backend_tests/rpc-audit-test.clj` (full-row, telemetry-only, dual, no-op without flags, `insert`, `prepare-rpc-event`) and `rpc-team-test/accept-organization-invitation-audit-event` (three cases, exact props; counts matter: asserting with `first` passes even when a command emits the same name twice).
- **New Penpot frontend event**: create the data-event or implement `Event`/`PerformanceEvent` in `app.main.data.event`, with `::name`/`::type`/`::origin`. Honor `skip-audit?` if the action can resume. Remember: with no props schema, **your asserts are the contract**.
- **New admin-console event**: server action → add the backend-log call next to the mutation, best-effort so it never breaks the action; browser action → add a new event helper plus its test.
- **Never** put secrets in `props`; `clean-props` is a last defense, not an excuse.
- **Do not duplicate events**: if the backend already emits an event for an action, do not add a frontend (or admin-console browser) event for the same action — the backend one wins because it is reliable. A second event for the same action is only acceptable with a strong reason (for example the backend cannot see it at all), and then it must be clearly justified.
- **Follow the naming policy**: use kebab-case names (`"create-organization"`, not `"createOrganization"`), and before inventing prop keys, look at similar existing events and reuse their key names (`organization-id`, `team-id`, `file-id`, `event-origin`...). Same concept, same key spelling — that is what keeps the data queryable later.

## 15. Code map

- Backend producer: `backend/src/app/loggers/audit.clj` (`submit`, `submit*`, `insert`, `prepare-rpc-event`, `event-from-rpc-params`, telemetry filters), `backend/src/app/rpc.clj` (`wrap-audit`), `backend/src/app/rpc/commands/audit.clj` (`push-audit-events`, `get-environment-data`, deprecated `get-enabled-flags`), `backend/src/app/rpc/management/nitrate.clj` (`push-audit-events` Nitrate→Penpot), `backend/src/app/http/middleware.clj` + `backend/src/app/http/management.clj` (shared-key auth).
- Penpot frontend producer: `frontend/src/app/main/data/event.cljs` (collector, buffer, send), `frontend/src/app/main/repo.cljs` + `frontend/src/app/util/http.cljs` (headers), `frontend/src/app/main/data/nitrate.cljs` (Nitrate membership events).
- Admin-console producer (private repo, no paths cited here): server-side organization/subscription events through the management `push-audit-events`, browser-side UI events through the public `push-audit-events`.
- Consumers: `backend/src/app/loggers/webhooks.clj`, `backend/src/app/loggers/database.clj`, `backend/src/app/loggers/mattermost.clj`, `backend/src/app/tasks/telemetry.clj`.
- Archive/GC: `backend/src/app/loggers/audit/archive_task.clj`, `backend/src/app/loggers/audit/gc_task.clj`, wiring and crons in `backend/src/app/main.clj`.
- Nexus (private repo, no paths cited here): archive ingest, ordered consumers (local profile mirror, PostHog SaaS events, PostHog telemetry), telemetry endpoint, analytics views.
- Tests: `backend_tests/rpc-audit-test.clj`, `rpc-team-test/accept-organization-invitation-audit-event`.
