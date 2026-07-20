// Shared async wait helpers. Not a `*.test.ts`, so the runner's glob doesn't
// pick it up as a test file; it's only imported by the tests that need it.

/**
 * Polls `predicate` until it returns truthy or the timeout elapses. Prefer this
 * over a fixed `sleep` whenever there is an observable post-condition to wait
 * for: it settles as soon as the condition holds (faster) and tolerates a slow
 * backend (less flaky). It does not throw on timeout — the test's own assertion
 * reports the failure.
 */
export async function waitFor(
  predicate: () => boolean,
  {
    timeout = 2000,
    interval = 50,
  }: { timeout?: number; interval?: number } = {},
): Promise<void> {
  const start = Date.now();
  while (!predicate() && Date.now() - start < timeout) {
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
}
