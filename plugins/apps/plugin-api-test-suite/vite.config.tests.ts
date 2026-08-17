import { iifeConfig } from './vite.config.iife';

// Builds the test cases as a single self-executing (IIFE) bundle that publishes
// the discovered tests on `globalThis.__penpotReloadedTests`. The UI "Reload"
// button fetches this file and the plugin sandbox `eval`s it to pick up edited
// tests without reopening the plugin. Rebuilt on save by the `watch` script.
// See vite.config.iife.ts for the shared bundle config.
export default iifeConfig('tests-bundle', 'src/tests-bundle.ts');
