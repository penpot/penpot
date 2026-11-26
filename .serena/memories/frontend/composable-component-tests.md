# Composable component tests

- Use `mem:frontend/composable-component-tests/cljs` to test component behavior through workspace events in a minimal frontend harness. Use `mem:frontend/composable-component-tests/plugin` to exercise that behavior through the Plugin API in the full browser app.
- Bind each producing operation or strategy once and query that same instance for what it produced, which choice ran, or whether it applied. Reading the document directly for those answers loses the identity of the operation that ran in a variant and duplicates retrieval logic.
- Drive real changes, workspace events or Plugin API calls; direct writes bypass the automatic propagation under test. Resolve targets by role when each operation runs, so a role rebound by a previous step still works. Compose only valid variants rather than treating invalid combinations as skipped coverage.
- For component behavior, swap semantics and integrity see `mem:common/components`; for test selection see `mem:frontend/testing`.
