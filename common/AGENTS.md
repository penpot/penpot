# Penpot Common – Agent Instructions

A shared module with code written in Clojure, ClojureScript, and
JavaScript. Contains multiplatform code that can be used and executed
from the frontend, backend, or exporter modules. It uses Clojure reader
conditionals to specify platform-specific implementations.

## General Guidelines

To ensure consistency across the Penpot stack, all contributions must adhere to
these criteria:

### 1. Testing & Validation

If code is added or modified in `src/`, corresponding tests in
`test/common_tests/` must be added or updated.

  * **Environment:** Tests should run in both JS (Node.js) and JVM environments.
* **Location:** Place tests in the `test/common_tests/` directory, following the
  namespace structure of the source code (e.g., `app.common.colors` ->
  `common-tests.colors-test`).
* **Execution:** Tests should be executed on both JS (Node.js) and JVM environments:
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
  * Use `pnpm run fmt` to fix all formatting issues (`pnpm run
    fmt:clj` or `pnpm run fmt:js` for isolated formatting fix).

## Code Conventions

### Namespace Overview

The source is located under `src` directory and this is a general overview of
namespaces structure:

- `app.common.types.*` – Shared data types for shapes, files, pages using Malli schemas
- `app.common.schema` – Malli abstraction layer, exposes the most used functions from malli
- `app.common.geom.*` – Geometry and shape transformation helpers
- `app.common.data` – Generic helpers used across the entire application
- `app.common.math` – Generic math helpers used across the entire application
- `app.common.json` – Generic JSON encoding/decoding helpers
- `app.common.data.macros` – Performance macros used everywhere


### Reader Conditionals

We use reader conditionals to differentiate implementations depending on the
target platform where the code runs:

```clojure
#?(:clj  (import java.util.UUID)
   :cljs (:require [cljs.core :as core]))
```

Both frontend and backend depend on `common` as a local library (`penpot/common
{:local/root "../common"}`).

