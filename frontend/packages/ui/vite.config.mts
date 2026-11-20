/// <reference types='vitest' />
import { defineConfig, esmExternalRequirePlugin } from "vite";
import react from "@vitejs/plugin-react";
import dts from "vite-plugin-dts";
import * as path from "node:path";
import { copyFileSync, mkdirSync } from "node:fs";

const externalDeps = ["react", "react-dom", "react/jsx-runtime"];

export const copyCssPlugin = (packageDir = import.meta.dirname) => ({
  name: "copy-css",
  closeBundle: () => {
    const cssPath = path.resolve(packageDir, "dist/ui.css");
    const destinationPath = path.resolve(
      packageDir,
      "../../resources/public/css/ui.css",
    );

    mkdirSync(path.dirname(destinationPath), { recursive: true });
    copyFileSync(cssPath, destinationPath);
  },
});

export default defineConfig(() => ({
  root: import.meta.dirname,
  css: {
    preprocessorOptions: {
      scss: {
        loadPaths: [path.resolve(import.meta.dirname, "../../src/app/main/ui")],
      },
    },
  },
  plugins: [
    react({
      babel: {
        plugins: ["babel-plugin-react-compiler"],
      },
    }),
    dts({
      entryRoot: "src",
      tsconfigPath: path.join(import.meta.dirname, "tsconfig.lib.json"),
      pathsToAliases: false,
    }),
    copyCssPlugin(),
  ],
  build: {
    outDir: "dist/",
    emptyOutDir: true,
    reportCompressedSize: true,
    commonjsOptions: {
      transformMixedEsModules: true,
    },
    lib: {
      entry: {
        index: "src/index.ts",
        modal: "src/modal.ts",
        menu: "src/menu.ts",
      },
      name: "ui",
      formats: ["es" as const],
    },
    rollupOptions: {
      // Vendored CJS-only deps (e.g. use-sync-external-store) call
      // require("react") internally. Rolldown keeps require() calls
      // against external modules as-is instead of converting them to
      // import, which breaks in the browser where require() doesn't
      // exist. esmExternalRequirePlugin both marks these as external and
      // rewrites those calls to real ESM imports.
      // https://rolldown.rs/in-depth/bundling-cjs#require-external-modules
      plugins: [esmExternalRequirePlugin({ external: externalDeps })],
    },
  },
  test: {
    name: "ui",
    watch: false,
    globals: true,
    environment: "jsdom",
    include: ["{src,tests}/**/*.{test,spec}.{js,mjs,cjs,ts,mts,cts,jsx,tsx}"],
    reporters: ["default"],
    coverage: {
      reportsDirectory: "../../coverage/libs/ui",
      provider: "v8" as const,
    },
  },
}));
