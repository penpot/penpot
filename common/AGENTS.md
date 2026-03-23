# Penpot Common – Agent Instructions

A shared module with code written in Clojure, ClojureScript and
JavaScript. Contains multplatform code that can be used and executed
from frontend, backend or exporter modules. It uses clojure reader
conditionals for specify platform specific implementation.

## General Guidelines

This is a golden rule for common module development. To ensure
consistency across the penpot stack, all contributions must adhere to
these criteria:

### 1. Testing & Validation

If code is added or modified in `src/`, corresponding tests in
`test/common_tests/` must be added or updated.

* **Environment:** Tests should run in a JS (nodejs) and JVM
* **Location:** Place tests in the `test/common_tests/` directory, following the
  namespace structure of the source code (e.g., `app.common.colors` ->
  `common-tests.colors-test`).
* **Execution:** The tests should be executed on both: JS (nodejs) and JVM environments
  * **Isolated:**
    * JS: To run a focused ClojureScript unit test: edit the
    `test/common_tests/runner.cljs` to narrow the test suite, then
    `pnpm run test:js`.
    * JVM: `pnpm run test:jvm --focus common-tests.my-ns-test`
  * **Regression:**
    * JS: Run `pnpm run test:js` without modifications on the runner (preferred)
    * JVM: Run `pnpm run test:jvm`

### 2. Code Quality & Formatting

* **Linting:** All code changes must pass linter checks:
  * Run `pnpm run lint:clj` for CLJ/CLJS/CLJC
* **Formatting:** All code changes must pass the formatting check
  * Run `pnpm run check-fmt:clj` for CLJ/CLJS/CLJC
  * Run `pnpm run check-fmt:js` for JS
  * Use the `pnpm run fmt` fix all the formatting issues (`pnpm run
    fmt:clj` or `pnpm run fmt:js` for isolated formatting fix)

## Code Conventions

### Namespace Overview

The source is located under `src` directory and this is a general overview of
namespaces structure:

- `app.common.types.*` – Shared data types for shapes, files, pages using Malli schemas
- `app.common.schema` – Malli abstraction layer, exposes the most used functions from malli
- `app.common.geom.*` – Geometry and shape transformation helpers
- `app.common.data` – Generic helpers used around all application
- `app.common.math` – Generic math helpers used around all aplication
- `app.common.json` – Generic JSON encoding/decoding helpers
- `app.common.data.macros` – Performance macros used everywhere


### Reader Conditionals

We use reader conditionals to target for differentiate an
implementation depending on the target platform where code should run:

```clojure
#?(:clj  (import java.util.UUID)
   :cljs (:require [cljs.core :as core]))
```

Both frontend and backend depend on `common` as a local library (`penpot/common
{:local/root "../common"}`).

