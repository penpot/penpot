import { runTests } from '../framework/runner';
import { getTests } from '../framework/registry';

// In-sandbox CI entry point. Built as a standalone IIFE bundle (headless.js) and
// evaluated inside a real Penpot plugin sandbox by the out-of-sandbox driver
// `ci/run-ci.ts` (note: distinct from this `src/ci/` directory). It runs every
// test and reports results + coverage through `console.log` markers that the
// Playwright driver parses. It has no UI.

// Auto-discover the same tests used by the UI plugin.
import.meta.glob('../tests/*.test.ts', { eager: true });

async function main() {
  // Set by the mocked-backend runner (MOCK_BACKEND=1) before this bundle loads,
  // so backend-result-dependent tests tagged `skipIfMocked` are excluded.
  const skipMocked = !!(
    globalThis as unknown as { __PLUGIN_SUITE_MOCKED__?: boolean }
  ).__PLUGIN_SUITE_MOCKED__;

  // Set by the driver from TEST_FILTER: run only tests whose group or name
  // contains the given substring (case-insensitive).
  const filter = (
    globalThis as unknown as { __PLUGIN_SUITE_FILTER__?: string }
  ).__PLUGIN_SUITE_FILTER__?.toLowerCase();
  const ids = filter
    ? getTests()
        .filter(
          (t) =>
            t.name.toLowerCase().includes(filter) ||
            t.group.toLowerCase().includes(filter),
        )
        .map((t) => t.id)
    : ('all' as const);

  // Stream each result as it completes (not just at the end) so the runner sees
  // progress and partial output survives if a later test hangs to its timeout.
  const { summary, coverage, skipped } = await runTests(
    ids,
    (result) => {
      if (result.status !== 'running') {
        console.log('__TEST_RESULT__ ' + JSON.stringify(result));
      }
    },
    { skipMocked },
  );

  console.log('__TEST_COVERAGE__ ' + JSON.stringify(coverage));
  console.log('__TEST_SKIPPED__ ' + JSON.stringify(skipped));
  console.log('__TEST_DONE__ ' + JSON.stringify(summary));
}

main().catch((err) => {
  const message = err instanceof Error ? err.message : String(err);
  console.log('__TEST_FATAL__ ' + JSON.stringify({ message }));
});
