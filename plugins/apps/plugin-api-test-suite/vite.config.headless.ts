import { iifeConfig } from './vite.config.iife';

// Builds the CI test entry as a single self-executing (IIFE) bundle, evaluated
// inside the Penpot plugin sandbox via `globalThis.ɵloadPlugin({ code })` by the
// CI runner. See vite.config.iife.ts for the shared bundle config.
export default iifeConfig('headless', 'src/ci/headless.ts');
