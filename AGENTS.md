# Penpot – Copilot Instructions

## Architecture Overview

Penpot is a full-stack design tool composed of several distinct components:

| Component | Language | Role |
|-----------|----------|------|
| `frontend/` | ClojureScript + SCSS | Single-page React app (design editor) |
| `backend/` | Clojure (JVM) | HTTP/RPC server, PostgreSQL, Redis |
| `common/` | Cljc (shared Clojure/ClojureScript) | Data types, geometry, schemas, utilities |
| `exporter/` | ClojureScript (Node.js) | Headless Playwright-based export (SVG/PDF) |
| `render-wasm/` | Rust → WebAssembly | High-performance canvas renderer using Skia |
| `mcp/` | TypeScript | Model Context Protocol integration |
| `plugins/` | TypeScript | Plugin runtime and example plugins |

The monorepo is managed with `pnpm` workspaces. The `manage.sh`
orchestrates cross-component builds. `run-ci.sh` defines the CI
pipeline.

---

## Build, Test & Lint Commands

### Frontend (`cd frontend`)

Run `./scripts/setup` for setup all dependencies.


```bash
# Dev
pnpm run watch:app            # Full dev build (WASM + CLJS + assets)

# Production Build
./scripts/build

# Tests
pnpm run test                 # Build ClojureScript tests + run node target/tests/test.js
pnpm run watch:test           # Watch + auto-rerun on change
pnpm run test:e2e             # Playwright e2e tests
pnpm run test:e2e --grep "pattern"  # Single e2e test by pattern

# Lint
pnpm run lint:js              # format and linter check for JS
pnpm run lint:clj             # format and linter check for CLJ
pnpm run lint:scss            # prettier check for SCSS

# Code formatting
pnpm run fmt:clj              # Format CLJ
pnpm run fmt:js               # prettier for JS
pnpm run fmt:scss             # prettier for SCSS
```

To run a focused ClojureScript unit test: edit
`test/frontend_tests/runner.cljs` to narrow the test suite, then `pnpm
run build:test && node target/tests/test.js`.


### Backend (`cd backend`)

```bash
# Tests (Kaocha)
clojure -M:dev:test                                    # Full suite
clojure -M:dev:test --focus backend-tests.my-ns-test   # Single namespace

# Lint / Format
pnpm run lint:clj
pnpm run fmt:clj
```

Test config is in `backend/tests.edn`; test namespaces match `.*-test$` under `test/`.


### Common (`cd common`)

```bash
pnpm run test                 # Build + run node target/tests/test.js
pnpm run watch:test           # Watch mode
pnpm run lint:clj
pnpm run fmt:clj
```

### Render-WASM (`cd render-wasm`)

```bash
./test                        # Rust unit tests (cargo test)
./build                       # Compile to WASM (requires Emscripten)
cargo fmt --check
./lint --debug
```

## Key Conventions

### Namespace Structure

**Backend:**
- `app.rpc.commands.*` – RPC command implementations (`auth`, `files`, `teams`, etc.)
- `app.http.*` – HTTP routes and middleware
- `app.db.*` – Database layer
- `app.tasks.*` – Background job tasks
- `app.main` – Integrant system setup and entrypoint
- `app.loggers` – Internal loggers (auditlog, mattermost, etc) (do not be confused with `app.common.loggin`)

**Frontend:**
- `app.main.ui.*` – React UI components (`workspace`, `dashboard`, `viewer`)
- `app.main.data.*` – Potok event handlers (state mutations + side effects)
- `app.main.refs` – Reactive subscriptions (okulary lenses)
- `app.main.store` – Potok event store
- `app.util.*` – Utilities (DOM, HTTP, i18n, keyboard shortcuts)

**Common:**
- `app.common.types.*` – Shared data types for shapes, files, pages
- `app.common.schema` – Malli validation schemas
- `app.common.geom.*` – Geometry utilities
- `app.common.data.macros` – Performance macros used everywhere

### Backend RPC Commands

All API calls go through a single RPC endpoint: `POST /api/rpc/command/<cmd-name>`.

```clojure
(sv/defmethod ::my-command
  {::rpc/auth true            ;; requires auth
   ::doc/added "1.18"
   ::sm/params [:map ...]     ;; malli input schema
   ::sm/result [:map ...]}    ;; malli output schema
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id] :as params}]
  ;; return a plain map or throw
  {:id (uuid/next)})
```

### Frontend State Management (Potok)

State is a single atom managed by a Potok store. Events implement protocols:

```clojure
(defn my-event [data]
  (ptk/reify ::my-event
    ptk/UpdateEvent
    (update [_ state]           ;; synchronous state transition
      (assoc state :key data))

    ptk/WatchEvent
    (watch [_ state stream]     ;; async: returns an observable
      (->> (rp/cmd! :some-rpc-command params)
           (rx/map success-event)
           (rx/catch error-handler)))

    ptk/EffectEvent
    (effect [_ state _]         ;; pure side effects (DOM, logging)
      (.focus (dom/get-element "id")))))
```

Dispatch with `(st/emit! (my-event data))`. Read state via reactive
refs: `(deref refs/selected-shapes)`.  Prefer helpers from
`app.util.dom` instead of using direct dom calls, if no helper is
available, prefer adding a new helper for handling it and the use the
new helper.


### CSS Modules Pattern

Styles are co-located with components. Each `.cljs` file has a corresponding `.scss` file:

```clojure
;; In the component namespace:
(require '[app.main.style :as stl])

;; In the render function:
[:div {:class (stl/css :container :active)}]

;; Conditional:
[:div {:class (stl/css-case :some-class true :selected (= drawtool :rect))}]

;; When you need concat an existing class:
[:div {:class [existing-class (stl/css-case :some-class true :selected (= drawtool :rect))]}]

```

### Performance Macros (`app.common.data.macros`)

Always prefer these macros over their `clojure.core` equivalents — they compile to faster JavaScript:

```clojure
(dm/select-keys m [:a :b])     ;; ~6x faster than core/select-keys
(dm/get-in obj [:a :b :c])     ;; faster than core/get-in
(dm/str "a" "b" "c")           ;; string concatenation
```

### Shared Code (cljc)

Files in `common/src/app/common/` use reader conditionals to target both runtimes:

```clojure
#?(:clj  (import java.util.UUID)
   :cljs (:require [cljs.core :as core]))
```

Both frontend and backend depend on `common` as a local library (`penpot/common {:local/root "../common"}`).


### Component Definition (Rumext / React)

The codebase has several kind of components, some of them use legacy
syntax. The current and the most recent syntax uses `*` suffix on the
name. This indicates to the `mf/defc` macro apply concrete rules on
how props should be treated.

```clojure
(mf/defc my-component*
  {::mf/wrap [mf/memo]}         ;; React.memo
  [{:keys [name on-click]}]
  [:div {:class (stl/css :root)
         :on-click on-click}
   name])
```

Hooks: `(mf/use-state)`, `(mf/use-effect)`, `(mf/use-memo)` – analgous to react hooks.


The component usage should always follow the `[:> my-component*
props]`, where props should be a map literal or symbol pointing to
javascript props objects. The javascript props object can be created
manually `#js {:data-foo "bar"}` or using `mf/spread-object` helper
macro.

---

## Commit Guidelines

Format: `<emoji-code> <subject>`

```
:bug: Fix unexpected error on launching modal

Optional body explaining the why.

Signed-off-by: Fullname <email>
```

**Subject rules:** imperative mood, capitalize first letter, no
trailing period, ≤ 80 characters. Add an entry to `CHANGES.md` if
applicable.

**Code patches must include a DCO sign-off** (`git commit -s`).

| Emoji | Emoji-Code | Use for |
|-------|------|---------|
| 🐛 | `:bug:` | Bug fix |
| ✨ | `:sparkles:` | Improvement |
| 🎉 | `:tada:` | New feature |
| ♻️ | `:recycle:` | Refactor |
| 💄 | `:lipstick:` | Cosmetic changes |
| 🚑 | `:ambulance:` | Critical bug fix |
| 📚 | `:books:` | Docs |
| 🚧 | `:construction:` | WIP |
| 💥 | `:boom:` | Breaking change |
| 🔧 | `:wrench:` | Config update |
| ⚡ | `:zap:` | Performance |
| 🐳 | `:whale:` | Docker |
| 📎 | `:paperclip:` | Other non-relevant changes |
| ⬆️ | `:arrow_up:` | Dependency upgrade |
| ⬇️ | `:arrow_down:` | Dependency downgrade |
| 🔥 | `:fire:` | Remove files or code |
| 🌐 | `:globe_with_meridians:` | Translations |
