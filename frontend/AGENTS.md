# Penpot Frontend – Agent Instructions

ClojureScript based frontend application that uses React, RxJS as main
architectural pieces.


## General Guidelines

This is a golden rule for frontend development standards. To ensure consistency
across the penpot stack, all contributions must adhere to these criteria:


### 1. Testing & Validation

#### Unit Tests

If code is added or modified in `src/`, corresponding tests in
`test/frontend_tests/` must be added or updated.

* **Environment:** Tests should run in a Node.js or browser-isolated
  environment without requiring the full application state or a
  running backend. Test are developed using cljs.test.
* **Mocks & Stubs:** * Use proper mocks for any side-effecting
  functions (e.g., API calls, storage access).
  * Avoid testing through the UI (DOM), we have e2e tests for that/
  * Use `with-redefs` or similar ClojureScript mocking utilities to isolate the logic under test.
* **No Flakiness:** Tests must be deterministic. Do not use `setTimeout` or real
  network calls. Use synchronous mocks for asynchronous workflows where
  possible.
* **Location:** Place tests in the `test/frontend_tests/` directory, following the
  namespace structure of the source code (e.g., `app.utils.timers` ->
  `frontend-tests.util-timers-test`).
* **Execution:**
  * **Isolated:** To run a focused ClojureScript unit test: edit the
    `test/frontend_tests/runner.cljs` to narrow the test suite, then `pnpm run
    test`.
  * **Regression:** Run `pnpm run test` without modifications on the runner (preferred)


#### Integration Tests (Playwright)

Integration tests are developed under `frontend/playwright` directory, we use
mocks for remove communication with backend.

You should not add, modify or run the integration tests unless it exlicitly asked for.


```
pnpm run test:e2e                   # Playwright e2e tests
pnpm run test:e2e --grep "pattern"  # Single e2e test by pattern
```

Ensure everything installed before executing tests with `./scripts/setup` script.


### 2. Code Quality & Formatting

* **Linting:** All code changes must pass linter checks:
  * Run `pnpm run lint:clj` for CLJ/CLJS/CLJC
  * Run `pnpm run lint:js` for JS
  * Run `pnpm run lint:scss` for SCSS
* **Formatting:** All code changes must pass the formatting check
  * Run `pnpm run check-fmt:clj` for CLJ/CLJS/CLJC
  * Run `pnpm run check-fmt:js` for JS
  * Run `pnpm run check-fmt:scss` for SCSS
  * Use the `pnpm run fmt` fix all the formatting issues (`pnpm run fmt:clj`,
    `pnpm run fmt:js` or `pnpm run fmt:scss` for isolated formatting fix)

### 3. Implementation Rules

* **Logic vs. View:** If logic is embedded in an UI component, extract it into a
  function in the same namespace if is only used locally or look for a helper
  namespace to make it unit-testable.


## Code Conventions

### Namespace Overview

The source is located under `src` directory and this is a general overview of
namespaces structure:

- `app.main.ui.*` – React UI components (`workspace`, `dashboard`, `viewer`)
- `app.main.data.*` – Potok event handlers (state mutations + side effects)
- `app.main.refs` – Reactive subscriptions (okulary lenses)
- `app.main.store` – Potok event store
- `app.util.*` – Utilities (DOM, HTTP, i18n, keyboard shortcuts)


### State Management (Potok)

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

### UI Components (React & Rumext: mf/defc)

The codebase contains various component patterns. When creating or refactoring
components, follow the Modern Syntax rules outlined below.

#### 1. The * Suffix Convention

The most recent syntax uses a * suffix in the component name (e.g.,
my-component*). This suffix signals the mf/defc macro to apply specific rules
for props handling and destructuring and optimization.

#### 2. Component Definition

Modern components should use the following structure:

```clj
(mf/defc my-component*
  {::mf/wrap [mf/memo]}         ;; Equivalent to React.memo
  [{:keys [name on-click]}]     ;; Destructured props
  [:div {:class (stl/css :root)
         :on-click on-click}
   name])
```

#### 3. Hooks

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


#### 4. Component Usage (Hiccup Syntax)

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

#### 5. Styles

##### Styles on component code
Styles are co-located with components. Each `.cljs` file has a corresponding
`.scss` file.

Example of clojurescript code for reference classes defined on styles (we use
CSS modules pattern):

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

##### General rules for styling

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


### Performance Macros (`app.common.data.macros`)

Always prefer these macros over their `clojure.core` equivalents — they compile to faster JavaScript:

```clojure
(dm/select-keys m [:a :b])     ;; ~6x faster than core/select-keys
(dm/get-in obj [:a :b :c])     ;; faster than core/get-in
(dm/str "a" "b" "c")           ;; string concatenation
```

### Configuration

`src/app/config.clj` reads globally defined variables and exposes precomputed
configuration vars ready to be used from other parts of the application

