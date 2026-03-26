---
name: penpot-frontend
description: Guidelines and workflows for the Penpot ClojureScript React frontend.
---

# Penpot Frontend Skill

This skill provides guidelines and workflows for the Penpot ClojureScript React frontend.

## Testing & Validation
- **Isolated tests:** Edit `test/frontend_tests/runner.cljs` to narrow the test suite, then run `pnpm run test`
- **Regression tests:** `pnpm run test` (without modifications on the runner)
- **Integration tests:** `pnpm run test:e2e` or `pnpm run test:e2e --grep "pattern"` (do not modify e2e tests unless explicitly asked).

## Code Quality
- **Linting:** 
  - `pnpm run lint:clj`
  - `pnpm run lint:js`
  - `pnpm run lint:scss`
- **Formatting:** 
  - Check: `pnpm run check-fmt:clj`, `pnpm run check-fmt:js`, `pnpm run check-fmt:scss`
  - Fix: `pnpm run fmt:clj`, `pnpm run fmt:js`, `pnpm run fmt:scss`

## Architecture & Conventions
- Uses React and RxJS (Potok for state management).
- Modern components use the `*` suffix (e.g., `my-component*`) and the `mf/defc` macro.
- Hooks: `mf/use-state`, `mf/use-effect`, `mf/use-memo`, `mf/use-fn`. Prefer macros `mf/with-effect` and `mf/with-memo`.
- Styles: Use CSS custom properties from `_sizes.scss` and tokens from `ds/colors.scss`. Avoid deep selector nesting.
