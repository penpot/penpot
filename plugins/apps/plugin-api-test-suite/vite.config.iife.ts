import { defineConfig, type UserConfig } from 'vite';

/**
 * Shared config for the two single-file IIFE bundles (`headless.js`,
 * `tests-bundle.js`). Both are self-executing chunks with no `import`/`export`
 * statements so they can be evaluated directly inside the Penpot plugin sandbox
 * (via `globalThis.ɵloadPlugin({ code })` for headless, or the UI "Reload"
 * button's `eval` for the tests bundle). They differ only by their entry module.
 *
 * `emptyOutDir` stays false so a `watch` rebuild of one bundle never wipes the
 * sibling outputs in the shared `dist` directory.
 */
export function iifeConfig(name: string, entry: string): UserConfig {
  return defineConfig({
    root: __dirname,
    resolve: {
      tsconfigPaths: true,
    },
    build: {
      outDir: '../../dist/apps/plugin-api-test-suite',
      emptyOutDir: false,
      reportCompressedSize: false,
      rollupOptions: {
        input: {
          [name]: entry,
        },
        output: {
          format: 'iife',
          entryFileNames: '[name].js',
        },
      },
    },
  });
}
