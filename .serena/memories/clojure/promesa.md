# Promesa (funcool, 12.0.1) — verified API shapes

The promise lib of the monorepo: JVM (backend, `promesa.exec` thread
pools) and CLJS (frontend, exporter). Declared in `common/deps.edn`;
heavy users: `frontend/src/app/main/**` (Rx interop), `exporter/src/app/**`
(management client, auth), `backend/src/app/storage/s3.clj` and
`worker/cron.clj` (JVM await paths). Doc: `doc/promises.md` upstream.

## Two API families (verified against the 12.0.1 jar)

Argument order is inconsistent BY DESIGN; each op belongs to one of
two families:

**promise-first `([p f ...])` — pairs with `->` threading** (the
 promise arrives as the threaded first argument):
- `then`, `then'`, `bind`, `handle`, `finally`, `catch` (`([p f])`,
  2-arity with predicate: `([p pred-or-type f])`), `chain`
  `([p f & fs])`.

**fn-first `([f p ...])` — pairs with `->>` threading** (the promise
 arrives as the threaded LAST argument):
- `map`/`fmap`, `mapcat`/`mcat`, `hmap`/`hcat`, `fnly`, `merr`.

Every 3-arity in a family inserts its executor FIRST for the fn-first
family (`([executor f p])`) and LAST for the promise-first family
(`([p f executor])`).

**What `f` must return:**
- `then`/`catch`/`fmap`/`hmap`: plain value OK — auto unwrapping.
- the bind-style ops `mcat` and `merr`: **`f` MUST return a promise
  instance** (`merr` may also throw). A plain value does NOT get
  wrapped: the impl's `fbind` pushes a BIND task that demands a
  thenable and crashes with `TypeError: expected thenable`
  (first-hand crash in the exporter worker, 2026-10-07).

`finally`/`fnly`: run `f`, mirror the original promise value.

## promesa's own -> / ->> macros (do NOT confuse with clojure.core)

`p/->` and `p/->>` exist in `promesa.core` (12.x). Their purpose is NOT
arg positioning: they expand to a `chain` over `then` and REALIZE the
promise at each step, so every form receives the realized value
(e.g. `(p/-> (js/fetch ...) .-body .-status)`).

## Repo convention

**Prefer `->>` composition** over the map family (fn-first ops); it is
how new promise code should read:

  (->> (fetch ...)
       (p/fmap (fn [v] ...))
       (p/mcat (fn [v] (p/...))))

A `p/do` terminator inside `->>` receives the threaded promise as its
LAST expression — it runs AFTER the earlier exprs, so it can never act
as the chain's continuation; terminate by wrapping the chain in an
explicit `(p/do chain ...)` (`p/let` avoids the same trap).

Legacy code threads promises with `->` over the promise-first
family (`(-> p (p/then ...))`, `(p/then p f)` calls). Read the args in
that order when touching it; do not rewrite it unless the task touches
that code anyway.

For JVM blocking code, `promesa.exec/await!` exists but is deprecated
since 12.0.0 → `p/await`.

## Async test gotcha (CLJS)

An async cljs.test var only proves its chain when the chain is really
awaited: a broken link outside the `p/let`/`p/do` body ends silently as
"Ran N tests containing 0 assertions" with 0 failures. If a test passes
with 0 assertions, the chain broke upstream; add a print in the last
link (`CHAIN RAN`) to prove plumbing before trusting green.
