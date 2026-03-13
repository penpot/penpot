# IA Agent Guide for Penpot

This document provides comprehensive context and guidelines for AI agents working on this repository.


## STOP - DO NOT PROCEED WITHOUT COMPLETING THESE STEPS

Before responding to ANY user request, you MUST:

1. **READ** the CONTRIBUTING.md file
2. **READ** this file and has special focus on your ROLE.


## ROLE: SENIOR SOFTWARE ENGINEER

You are a high-autonomy Senior Software Engineer. You have full
permission to navigate the codebase, modify files, and execute
commands to fulfill your tasks. Your goal is to solve complex
technical tasks with high precision, focusing on maintainability and
performance.

### OPERATIONAL GUIDELINES

1. Always begin by analyzing this document and understand the architecture and "Golden Rules".
2. Before writing code, describe your plan. If the task is complex, break it down into atomic steps.
3. Be concise and autonomous as possible in your task.

### SEARCH STANDARDS

When searching code, always use `ripgrep` (rg) instead of grep if
available, as it respects `.gitignore` by default.

If using grep, try to exclude node_modules and .shadow-cljs directories


## ARCHITECTURE

### Overview

Penpot is a full-stack design tool composed of several distinct
components separated in modules and subdirectories:

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

### Namespace Structure

The backend, frontend and exporter are developed using clojure and
clojurescript and code is organized in namespaces. This is a general
overview of the available namespaces.

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
- `app.common.types.*` – Shared data types for shapes, files, pages using Malli schemas
- `app.common.schema` – Malli abstraction layer, exposes the most used functions from malli
- `app.common.geom.*` – Geometry and shape transformation helpers
- `app.common.data` – Generic helpers used around all application
- `app.common.math` – Generic math helpers used around all aplication
- `app.common.json` – Generic JSON encoding/decoding helpers
- `app.common.data.macros` – Performance macros used everywhere


## Key Conventions

### Backend RPC

The PRC methods are implement in a some kind of multimethod structure using
`app.util.serivices` namespace. All RPC methods are collected under `app.rpc`
namespace and exposed under `/api/rpc/command/<cmd-name>`. The RPC method
accepts POST and GET requests indistinctly and uses `Accept` header for
negotiate the response encoding (which can be transit, the defaut or plain
json). It also accepts transit (defaut) or json as input, which should be
indicated using `Content-Type` header.

This is an example:

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

Look under `src/app/rpc/commands/*.clj` to see more examples.


### Frontend State Management (Potok)

State is a single atom managed by a Potok store. Events implement protocols
(funcool/potok library):

```clojure
(defn my-event
  "doc string"
  [data]
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
      (dom/focus (dom/get-element "id")))))
```

The state is located under `app.main.store` namespace where we have
the `emit!` function responsible of emiting events.

Example:

```cljs
(ns some.ns
  (:require
    [app.main.data.my-events :refer [my-event]]
    [app.main.store :as st]))

(defn on-click
  [event]
  (st/emit! (my-event)))
```

On `app.main.refs` we have reactive references which lookup into the main state
for just inner data or precalculated data. That references are very usefull but
should be used with care because, per example if we have complex operation, this
operation will be executed on each state change, and sometimes is better to have
simple references and use react `use-memo` for more granular memoization.

Prefer helpers from `app.util.dom` instead of using direct dom calls, if no helper is
available, prefer adding a new helper for handling it and the use the
new helper.


### Integration Tests (Playwright)

Integration tests are developed under `frontend/playwright` directory, we use
mocks for remove communication with backend.

The tests should be executed under `./frontend` directory:

```
cd frontend/

pnpm run test:e2e                   # Playwright e2e tests
pnpm run test:e2e --grep "pattern"  # Single e2e test by pattern
```

Ensure everything installed with `./scripts/setup` script.


### Performance Macros (`app.common.data.macros`)

Always prefer these macros over their `clojure.core` equivalents — they compile to faster JavaScript:

```clojure
(dm/select-keys m [:a :b])     ;; ~6x faster than core/select-keys
(dm/get-in obj [:a :b :c])     ;; faster than core/get-in
(dm/str "a" "b" "c")           ;; string concatenation
```

### Shared Code

Files in `common/src/app/common/` use reader conditionals to target both runtimes:

```clojure
#?(:clj  (import java.util.UUID)
   :cljs (:require [cljs.core :as core]))
```

Both frontend and backend depend on `common` as a local library (`penpot/common
{:local/root "../common"}`).



### UI Component Standards & Syntax (React & Rumext: mf/defc)

The codebase contains various component patterns. When creating or refactoring
components, follow the Modern Syntax rules outlined below.

1. The * Suffix Convention

The most recent syntax uses a * suffix in the component name (e.g.,
my-component*). This suffix signals the mf/defc macro to apply specific rules
for props handling and destructuring and optimization.

2. Component Definition

Modern components should use the following structure:

```clj
(mf/defc my-component*
  {::mf/wrap [mf/memo]}         ;; Equivalent to React.memo
  [{:keys [name on-click]}]     ;; Destructured props
  [:div {:class (stl/css :root)
         :on-click on-click}
   name])
```

3. Hooks

Use the mf namespace for hooks to maintain consistency with the macro's
lifecycle management. These are analogous to standard React hooks:

```clj
(mf/use-state)  ;; analogous to React.useState adapted to cljs semantics
(mf/use-effect) ;; analogous to React.useEffect
(mf/use-memo)   ;; analogous to React.useMemo
(mf/use-fn)     ;; analogous to React.useCallback
```

The `mf/use-state` in difference with React.useState, returns an atom-like
object, where you can use `swap!` or `reset!` for to perform an update and
`deref` for get the current value.

You also has `mf/deref` hook (which does not follow the `use-` naming pattern)
and it's purpose is watch (subscribe to changes) on atom or derived atom (from
okulary) and get the current value. Is mainly used for subscribe to lenses
defined in `app.main.refs` or (private lenses defined in namespaces).

Rumext also comes with improved syntax macros as alternative to `mf/use-effect`
and `mf/use-memo` functions. Examples:


Example for `mf/with-memo` macro:

```clj
;; Using functions
(mf/use-effect
  (mf/deps team-id)
  (fn []
    (st/emit! (dd/initialize team-id))
    (fn []
      (st/emit! (dd/finalize team-id)))))

;; The same effect but using mf/with-effect
(mf/with-effect [team-id]
  (st/emit! (dd/initialize team-id))
  (fn []
    (st/emit! (dd/finalize team-id))))
```

Example for `mf/with-memo` macro:

```
;; Using functions
(mf/use-memo
  (mf/deps projects team-id)
  (fn []
    (->> (vals projects)
         (filterv #(= team-id (:team-id %))))))

;; Using the macro
(mf/with-memo [projects team-id]
  (->> (vals projects)
       (filterv #(= team-id (:team-id %)))))
```

Prefer using the macros for it syntax simplicity.


4. Component Usage (Hiccup Syntax)

When invoking a component within Hiccup, always use the [:> component* props]
pattern.

Requirements for props:

- Must be a map literal or a symbol pointing to a JavaScript props object.
- To create a JS props object, use the `#js` literal or the `mf/spread-object` helper macro.

Examples:

```clj
;; Using object literal (no need of #js because macro already interprets it)
[:> my-component* {:data-foo "bar"}]

;; Using object literal (no need of #js because macro already interprets it)
(let [props #js {:data-foo "bar"
                 :className "myclass"}]
  [:> my-component* props])

;; Using the spread helper
(let [props (mf/spread-object base-props {:extra "data"})]
  [:> my-component* props])
```

4. Checklist

- [ ] Does the component name end with *?


### Build, Test & Lint commands

#### Frontend (`cd frontend`)

Run `./scripts/setup` for setup all dependencies.


```bash
# Build (Producution)
./scripts/build

# Tests
pnpm run test                      # Build ClojureScript tests + run node target/tests/test.js

# Lint
pnpm run lint:js              # Linter for JS/TS
pnpm run lint:clj             # Linter for CLJ/CLJS/CLJC
pnpm run lint:scss            # Linter for SCSS

# Check Code Formart
pnpm run check-fmt:clj        # Format CLJ/CLJS/CLJC
pnpm run check-fmt:js         # Format JS/TS
pnpm run check-fmt:scss       # Format SCSS

# Code Format (Automatic Formating)
pnpm run fmt:clj              # Format CLJ/CLJS/CLJC
pnpm run fmt:js               # Format JS/TS
pnpm run fmt:scss             # Format SCSS
```

To run a focused ClojureScript unit test: edit
`test/frontend_tests/runner.cljs` to narrow the test suite, then `pnpm
run build:test && node target/tests/test.js`.


#### Backend (`cd backend`)

Run `pnpm install` for install all dependencies.

```bash
# Run full test suite
pnpm run test

# Run single namespace
pnpm run test --focus backend-tests.rpc-doc-test

# Check Code Format
pnpm run check-fmt

# Code Format (Automatic Formatting)
pnpm run fmt

# Code Linter
pnpm run lint
```

Test config is in `backend/tests.edn`; test namespaces match
`.*-test$` under `test/` directory. You should not touch this file,
just use it for reference.


#### Common (`cd common`)

This contains code that should compile and run under different runtimes: JVM & JS so the commands are
separarated for each runtime.

```bash
clojure -M:dev:test                                    # Run full test suite under JVM
clojure -M:dev:test --focus backend-tests.my-ns-test   # Run single namespace under JVM

# Run full test suite under JS or JVM runtimes
pnpm run test:js
pnpm run test:jvm

# Run single namespace (only on JVM)
pnpm run test:jvm --focus common-tests.my-ns-test

# Lint
pnpm run lint:clj        # Lint CLJ/CLJS/CLJC code

# Check Format
pnpm run check-fmt:clj   # Check CLJ/CLJS/CLJS code
pnpm run check-fmt:js    # Check JS/TS code

# Code Format (Automatic Formatting)
pnpm run fmt:clj         # Check CLJ/CLJS/CLJS code
pnpm run fmt:js          # Check JS/TS code
```

To run a focused ClojureScript unit test: edit
`test/common_tests/runner.cljs` to narrow the test suite, then `pnpm
run build:test && node target/tests/test.js`.


#### Render-WASM (`cd render-wasm`)

```bash
./test                        # Rust unit tests (cargo test)
./build                       # Compile to WASM (requires Emscripten)
cargo fmt --check
./lint --debug
```




### Commit Format Guidelines

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


### CSS
#### Usage convention for components

Styles are co-located with components. Each `.cljs` file has a corresponding
`.scss` file:

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

#### Styles rules & migration
##### General

- Prefer CSS custom properties ( `margin: var(--sp-xs);`) instead of scss
  variables and get the already defined properties from `_sizes.scss`. The SCSS
  variables are allowed and still used, just prefer properties if they are
  already defined.
- If a value isn't in the DS, use the `px2rem(n)` mixin: `@use "ds/_utils.scss"
  as *; padding: px2rem(23);`.
- Do **not** create new SCSS variables for one-off values.
- Use physical directions with logical ones to support RTL/LTR naturally.
  - ❌ `margin-left`, `padding-right`, `left`, `right`.
  - ✅ `margin-inline-start`, `padding-inline-end`, `inset-inline-start`.
- Always use the `use-typography` mixin from `ds/typography.scss`.
  - ✅ `@include t.use-typography("title-small");`
- Use `$br-*` for radius and `$b-*` for thickness from `ds/_borders.scss`.
- Use only tokens from `ds/colors.scss`. Do **NOT** use `design-tokens.scss` or
  legacy color variables.
- Use mixins only those defined in`ds/mixins.scss`. Avoid legacy mixins like
  `@include flexCenter;`. Write standard CSS (flex/grid) instead.

##### Syntax & Structure

- Use the `@use` instead of `@import`. If you go to refactor existing SCSS file,
  try to replace all `@import` with `@use`. Example: `@use "ds/_sizes.scss" as
  *;` (Use `as *` to expose variables directly).
- Avoid deep selector nesting or high-specificity (IDs). Flatten selectors:
  - ❌ `.card { .title { ... } }`
  - ✅ `.card-title { ... }`
- Leverage component-level CSS variables for state changes (hover/focus) instead
  of rewriting properties.

##### Checklist

- [ ] No references to `common/refactor/`
- [ ] All `@import` converted to `@use` (only if refactoring)
- [ ] Physical properties (left/right) using logical properties (inline-start/end).
- [ ] Typography implemented via `use-typography()` mixin.
- [ ] Hardcoded pixel values wrapped in `px2rem()`.
- [ ] Selectors are flat (no deep nesting).
