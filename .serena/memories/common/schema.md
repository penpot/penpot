# Common schema and coercion

- Start at `app.common.schema`. Its `json-transformer` supports key decoding and encoding for map-of schemas as well as value transformation. `coercer` decodes JSON-shaped input with that transformer and validates the result.
- `check-fn` returns a reusable validator that raises `ex-info` with default `:type :assertion`, `:code :data-validation` and `::explain`. Prefer a reusable checker in repeated or hot paths; `sm/check` creates one on each call.
- `check-fn` resolves its schema through `app.common.data.macros/lazy-delay`: an invalid schema can fail on namespace load in development and JVM tests, but on first use in production CLJS. See the macro docstring and target closure defines.
