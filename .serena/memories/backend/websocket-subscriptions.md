# Backend WebSocket Subscriptions

## Registry

- `app.http.websocket/state` holds `{:connections {conn-id → wsp} :by-profile {profile-id → #{conn-id}}}` in **one** atom, so a single `swap!` keeps both consistent; `register-connection`/`unregister-connection` maintain them together and drop a profile from the index once its last connection leaves.
- Each connection's own `::ws/state` atom holds its subscriptions (`::file-subscription`, `::team-subscription`). `state` is shadowed by `::ws/state` in every `handle-message` destructuring, so only `on-connect`, `on-disconnect` and the `repl-*` helpers touch the registry — reading the registry from a handler is a bug.
- `::profile-id` and `::session-id` on a connection live in the `app.http.websocket` namespace, while `::ws/id`, `::ws/state`, `::ws/output-ch`, `::ws/session-id` live in `app.util.websocket`. Mixing them up yields silently `nil` values (a `nil` `session-id` makes the channel's `:xf` drop **every** message).
- The registry is local to one backend instance. The message bus is shared (`mem:prod-infra/core`). Anything that must reach connections owned by another instance has to go through the bus — closing a connection from an RPC handler only ever works in a single-backend deployment.

## Revocation model

A subscription is authorized once, at `:subscribe-file`/`:subscribe-team`, and that decision used to be trusted for the whole connection life (GHSA-m53j-2766-6jqw, medium). Two independent mechanisms now bound it:

- **Announcement (primary).** A mutation that can change access publishes on `app.rpc.notifications/internal-revocation-topic` (`notify-permissions-changed` for one profile, `notify-team-permissions-changed` for one team id — the watcher resolves the members itself, so a large team is one event, not N). The `::revocation-watcher` Integrant component in `app.http.websocket` subscribes and calls `revalidate-profile-subscriptions`, which looks the profile up in `:by-profile` and re-checks every subscription with the same `has-read-permissions?` predicates the subscribe handlers authorize with — and `sql:file-permissions` additionally filters `deleted_at` on file, project and team, so a soft-deleted resource grants nothing. The event is only the trigger; the fresh check is the decision, which is what keeps a downgrade to `viewer` (still readable) and a non-member organization owner (read-only via `get-organization-owner-permissions`) from being mistaken for a revocation. Both publishers defer through `db/after-commit`, because the watcher checks on another connection and would otherwise see the pre-commit state.
- **Interval (safety net).** `start-relay` arms a `px/schedule` task per subscription that re-checks every `subscription-revalidation-interval` (default `default-revalidation-interval`, 5 min, a code constant in `app.http.websocket` and **not** in the `config.clj` default map, same rule as session lifetimes). It covers what no announcement can: Nitrate transferring organization ownership (an HTTP call to another service) and rows deleted straight over SQL. The re-check is a scheduled task, not a timer in the relay loop, because a loop-driven timer would be pushed away forever by steady edit traffic — exactly the busiest files. The task stops when the channel closes: every way a subscription can end closes it, so a finished relay never keeps polling. Cost is two or three permission queries per subscription per interval, plus one Nitrate call per team per interval when the `:admin-console` flag is on (cached 30 s); ticks run on promesa's default scheduler (one or two threads shared with every other scheduled task), so do not shorten the interval without sizing that. Two places fail open, deliberately: `still-authorized?` (a transient DB error logs and keeps the subscription; `InterruptedException` is rethrown) and the `catch Throwable` in `watch-revocations`.

Which mutations announce: `delete-team-member`, `leave-team`, `update-team-member-role` (member only), `delete-team`, `delete-file`, `delete-project`, `move-files`, `move-project` (one team event each). Anything not listed here is covered only by the interval. `delete-share-link` deliberately does not: a share-link visitor never passes the websocket subscribe check, because `check-read-permissions!` is called without a `share-id` and that arity only considers team membership.

`close-file-subscription`/`close-team-subscription` are idempotent: no-op on a mismatched id, and they `dissoc` the subscription so a repeat call and `:pointer-update` do not publish to a dead topic. Closing the channel is what ends the relay (`take!` on a closed channel returns `nil`).

## Topic and layering

- `internal-revocation-topic` is a fixed uuid that is never a real resource id, so it cannot collide with a file, team or profile topic — and deliberately not the `uuid/zero` system topic, which is `sp/pipe`d straight into the client output and anything published there reaches every client. No client can subscribe to the internal topic: `:subscribe-file`/`:subscribe-team` only accept ids that already passed a permission check, and `:broadcast`/`:pointer-update` only republish to ids the server already holds.
- The topic and the publish helpers live in `app.rpc.notifications`, not in `app.http.websocket`: the command namespaces cannot require the watcher, because `app.http.websocket` already requires `app.rpc.commands.files`/`teams`. The watcher keeps the registry, which is the part it owns.

## Time and channel traps in this namespace

- `take!` is the proven relay path here. `msgbus.clj` does use `sp/alts!` over data channels in production, so there is no general defect — but the relay keeps the simple blocking take plus an independent scheduled task instead.
- `ct/diff a b` returns **b − a** (`Duration/between`), so "time until a future instant" is `(inst-ms (ct/diff (ct/now) future))`.
- `ct/duration` unit keywords are singular: `{:millis 150}`, `{:minutes 5}`, never `:milliseconds`/`:days`.
- `still-authorized?` takes the predicate itself: `(boolean (authorized?))`. Calling `(boolean pred)` on the closure is always true and silently disables the interval — this exact bug shipped once and no test caught it until the resubscribe-counting test existed.

## Tests

`backend/test/backend_tests/http_websocket_test.clj` drives the real Redis-backed msgbus, so the revocation tests are end-to-end: register the connection in the real registry, run the real command, wait for the subscription to drop, then assert no content arrives. A connection built without `register-connection` is invisible to the watcher, so the watcher-based tests must go through `open-registered-connection`. Interval tests shorten the config via `with-redefs` on `cf/get` rather than waiting minutes. `with-clean-registry` runs as an `:each` fixture, so no test can leak a live relay (plus its revalidation task) into the next one. `captured-events` forwards the keyword args through to the real `mbus/pub!` — rebuilding them positionally publishes to the topic `:message` with a `nil` payload and silently untests every other notice. Interval tests take access away with a direct row delete, never with a command: a command announces, so the test would pass even with the interval removed. The resubscribe-counting test is the one that proves finished relays stop polling.

## See also

- `mem:backend/auth-permissions-product-domains` for the permission predicates and the not-found semantics that the re-checks reuse.
- `mem:prod-infra/core` for why the bus crosses backends and the registry does not.
- `mem:frontend/routing-app-shell-subtleties` for the client side: the frontend already reacts to `team-membership-change` by navigating away, which is why revocation needs no new client message.
- `.agents/plans/2026-10-07-websocket-subscription-revocation.md` for the original plan, and the sibling `.agents/plans/2026-10-05-leave-team-file-access-cleanup.md` whose hardened permission queries this re-verification depends on (without live team membership required in `sql:file-permissions`, the re-check would keep reporting access for a member who already left).
