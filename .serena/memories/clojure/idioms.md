# Clojure Idioms (verified)

Behaviors confirmed against the language/stdlib — do not re-derive from assumption; a wrong assumption here already cost a review round.

- `int?` is NOT 32-bit-only: true for `Long`, `Integer`, `Short`, `Byte` (fixed-precision integers). Clojure integer literals are `Long`, so `(int? 5000)` is true.
- `integer?` is the general integer predicate; prefer it when any integer kind must match, `int?` only when fixed precision is meant.
- `await` is a `cljs.core` macro asserting `(:async &env)`: it fails at compile time outside an `^:async` context, never silently.
- The analyzer reads `:async` only from the fn name meta and the `fn` operator meta; list-level meta is ignored (would make a MetaFn). `(fn ^:async [] …)` puts the meta on argv (pre/post only, NOT async).
- Valid: `(defn ^:async f)`, `(defn- ^:async f)`, `(^:async fn [] …)` (the latter is what stock `t/async` generates itself).
- `^:async` fns never throw synchronously (rejected promises instead); `try/catch/finally` supported; continuations are microtasks, timers and RxJS schedulers are macrotasks (FIFO).
