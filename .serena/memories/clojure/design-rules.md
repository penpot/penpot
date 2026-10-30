# Clojure Design Rules

How to shape a function in this codebase. Each rule came from a review round; do not re-derive from taste.

- **HARD RULE: no new `!` names.** Do not introduce a function or macro
  ending in `!`; side effects alone do not justify the suffix. Allowed
  exceptions, and only these: mutating public fns in `app.db` (`insert!`,
  `update!`, `delete!`, `run!`, `tx-run!`, ...) and fns whose contract is to
  work over transients (stdlib `conj!`/`assoc!`/`dissoc!`/`persistent!` and
  custom helpers over them). Add a new exception to `mem:clojure/idioms`
  with its justification **before** using it. Existing `!` elsewhere (e.g.
  `emit!`, `send!`, `submit!`, `validate-file!`) stays until its refactor,
  so a family stays consistent meanwhile; that is a reason to keep an old
  name, never to add a new one. Reporting helpers (log, sample-and-publish)
  never take `!`: the mutation happens in the `!` API they call.
- **Preconditions belong to the boundary, not to the core.** A function documents what it needs (hints, docstring) and assumes it. Absence checks live where the value enters the system: the caller that resolves optional objects, `ig/init-key`, the request handler — including `nil` that is optional-by-design (`when-let [cs (some-> server ...)]` at the entry point, as with `ConnectorStatistics` when the server option is off). A `(when (and (some? a) (some? b)) ...)` inside a leaf function means the guard is in the wrong place — if you cannot get `a` or `b`, you should not be calling it.
- **A static set of operations is written statically.** With a fixed, small set (four metrics) write the four calls. Do not build a collection and iterate it (`doseq` over a literal vector): it allocates, adds indirection and hides each operation. Use a collection when the set is dynamic (config, registry, input).
- **Functions that obtain data start with `get-` or `fetch-`.** A reader must see at a glance that the function reads (`get-file`, `get-snapshot`, `get-file-json`); a bare noun hides it (`file-json` says nothing about reading). New helpers that fetch by id follow this, never a `current-` prefix: the id parameter already says which one it is.
- **`?` marks a predicate, not a data prop.** A var or function that answers a question takes `?` (`export-error?`, `(defn editable? …)`); a map key that names a property does not (`:export-error`, never `:export-error?`), even when its value is a boolean. Existing `?` keys are legacy to remove gradually; do not add a new one.
- **Do not shape production code for tests.** No return values added just to assert them; assert on the observable effect. When preconditions move, move the tests with them.

Reference implementation: the http metrics samplers in `app.http` (`sample-worker-metrics` / `sample-connector-metrics` are guard-free; `sample-http-metrics` guards at the boundary).

See also: `mem:clojure/idioms` (language behaviors), `mem:testing` (TDD and test conventions).
