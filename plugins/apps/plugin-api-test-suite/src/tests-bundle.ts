import { getTests } from './framework/registry';

// Standalone bundle of all test cases, built as a single self-executing (IIFE)
// chunk by `vite.config.tests.ts` and rebuilt on every save by `watch`.
//
// The reload flow (see `src/plugin.ts`) fetches the freshly built bundle and
// `eval`s it inside the plugin sandbox. Importing the test modules registers them
// into this bundle's own registry; we then publish the discovered tests on
// `globalThis` so the sandbox can pick them up and swap them in without the user
// having to close and reopen the plugin.
import.meta.glob('./tests/*.test.ts', { eager: true });

(
  globalThis as unknown as { __penpotReloadedTests?: unknown }
).__penpotReloadedTests = getTests();
