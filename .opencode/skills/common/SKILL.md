---
name: penpot-common
description: Guidelines and workflows for the Penpot Common shared module.
---

# Penpot Common Skill

This skill provides guidelines and workflows for the Penpot Common shared module (Clojure/ClojureScript/JS).

## Testing & Validation
- **JS (Node) Isolated tests:** Edit `test/common_tests/runner.cljs` then run `pnpm run test:js`
- **JS (Node) Regression tests:** `pnpm run test:js`
- **JVM Isolated tests:** `pnpm run test:jvm --focus common-tests.my-ns-test`
- **JVM Regression tests:** `pnpm run test:jvm`

## Code Quality
- **Linting:** `pnpm run lint:clj`
- **Formatting:** 
  - Check: `pnpm run check-fmt:clj`, `pnpm run check-fmt:js`
  - Fix: `pnpm run fmt:clj`, `pnpm run fmt:js`

## Architecture & Conventions
- Multiplatform code used by frontend, backend, and exporter.
- Uses Clojure reader conditionals (`#?(:clj ... :cljs ...)`).
- Modifying common code requires testing across consumers (frontend, backend, exporter).
