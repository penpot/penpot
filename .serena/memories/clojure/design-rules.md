# Clojure Design Rules

How to shape a function in this codebase. Each rule came from a review round; do not re-derive from taste.

- **Preconditions belong to the boundary, not to the core.** A function documents what it needs (hints, docstring) and assumes it. Absence checks live where the value enters the system: the caller that resolves optional objects, `ig/init-key`, the request handler. A `(when (and (some? a) (some? b)) ...)` inside a leaf function means the guard is in the wrong place — if you cannot get `a` or `b`, you should not be calling it.
- **Optional-by-design data is guarded where the optionality is born.** Example: `ConnectorStatistics` is nil when the server option is off, so the guard is `when-let [cs (some-> server ...)]` at the entry point and the consumer assumes `cs`.
- **A static set of operations is written statically.** With a fixed, small set (four metrics) write the four calls. Do not build a collection and iterate it (`doseq` over a literal vector): it allocates, adds indirection and hides each operation. Use a collection when the set is dynamic (config, registry, input).
- **The name says what the function is; `!` says it mutates.** `!` marks functions whose contract is to change state or run a command (`run!`, `submit!`, `swap!`, `shutdown-now`). Reporting helpers (log, sample-and-publish) do not take it: the mutation happens in the `!` API they call. Keep a family consistent.
- **Do not shape production code for tests.** No return values added just to assert them; assert on the observable effect. When preconditions move, move the tests with them.

Reference implementation: the http metrics samplers in `app.http` (`sample-worker-metrics` / `sample-connector-metrics` are guard-free; `sample-http-metrics` guards at the boundary).

See also: `mem:clojure/idioms` (language behaviors), `mem:testing` (TDD and test conventions).
