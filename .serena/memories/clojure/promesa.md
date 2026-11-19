# Promesa

Promise library of the monorepo (frontend CLJS and common, `funcool/promesa` 12.0.1 in `common/deps.edn`). Its API has two call styles and mixing them in one expression is the trap: `fmap`/`mcat`/`merr` are **fn first, promise last** (`->>` friendly) and `then`/`then'`/`catch`/`handle` are **promise first, fn last** (`->` friendly). Convention: `fmap`/`mcat`/`merr` inside `->>` pipelines, `then`/`catch` with `->`. A `then` written inside a `->>` reads backwards and hides whether the step carries a value or a promise.
- `fmap` (alias `map`, both `(f p)`) is functor map: `f` returns a plain value.
- `mcat` (alias `mapcat`, both `(f p)`) is monadic bind: `f` must return a promise, and the result mirrors it.
- `then` also unwraps a promise returned by `f`, which is why it covers both cases at the cost of not naming which one it is; `then'` maps without unwrapping.
- `merr` (fn first) and `catch` (promise first) are the error side.
- `all` takes a collection of promises (a lazy seq works); `let` chains sequential bindings.
- Seam with the rx pipelines of the frontend: `beicon.v2.core/from` builds an observable from "arrays, collections, and promise instance" (`external/beicon`), so `(->> p (p/mcat f) (rx/from) (rx/map g))` is the usual bridge (`app.main.data.imports`, `app.util.zip`).
