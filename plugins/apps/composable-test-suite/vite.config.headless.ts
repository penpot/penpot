import { defineConfig } from "vite";

// Builds the CI test entry (src/ci/headless.ts) as a single self-executing
// (IIFE) bundle with no import/export statements, so it can be evaluated
// directly inside the Penpot plugin sandbox via `globalThis.ɵloadPlugin({
// code })` by the CI driver (ci/run-ci.ts). `emptyOutDir` stays false so this
// build never wipes the regular plugin outputs in dist/.
export default defineConfig({
    base: "./",
    build: {
        outDir: "dist",
        emptyOutDir: false,
        reportCompressedSize: false,
        rollupOptions: {
            input: {
                headless: "src/ci/headless.ts",
            },
            output: {
                format: "iife",
                entryFileNames: "[name].js",
            },
        },
    },
});
