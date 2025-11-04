import path from "node:path";
import fs from 'node:fs/promises';
import { defineConfig } from "vite";
import { coverageConfigDefaults } from "vitest/config";

async function waitFor(timeInMillis) {
  return new Promise(resolve =>
    setTimeout(_ => resolve(), timeInMillis)
  );
}

const wasmWatcherPlugin = (options = {}) => {
  return {
    name: "vite-wasm-watcher-plugin",
    configureServer(server) {
      server.watcher.add("../resources/public/js/render_wasm.wasm")
      server.watcher.add("../resources/public/js/render_wasm.js")
      server.watcher.on("change", async (file) => {
        if (file.includes("../resources/")) {
          // If we copy the files immediately, we end
          // up with an empty .js file (I don't know why).
          await waitFor(100)
          // copy files.
          await fs.copyFile(
            path.resolve(file),
            path.resolve('./src/wasm/', path.basename(file))
          )
          console.log(`${file} changed`);
        }
      });

      server.watcher.on("add", async (file) => {
        if (file.includes("../resources/")) {
          await fs.copyFile(
            path.resolve(file),
            path.resolve("./src/wasm/", path.basename(file)),
          );
          console.log(`${file} added`);
        }
      });

      server.watcher.on("unlink", (file) => {
        if (file.includes("../resources/")) {
          console.log(`${file} removed`);
        }
      });
    },
  };
};

export default defineConfig({
  plugins: [
    wasmWatcherPlugin()
  ],
  root: "./src",
  resolve: {
    alias: {
      "~": path.resolve("./src"),
    },
  },
  build: {
    minify: true,
    sourcemap: true,
    lib: {
      entry: "src/editor/TextEditor.js",
      name: "TextEditor",
      fileName: "TextEditor",
      formats: ["es"],
    },
  },
  test: {
    coverage: {
      enabled: true,
      exclude: ["main.js", "**/scripts/**", ...coverageConfigDefaults.exclude],
    },
    poolOptions: {
      threads: {
        singleThread: true,
      },
    },
    environmentOptions: {
      jsdom: {
        resources: "usable",
      },
    },
    browser: {
      name: "chromium",
      provider: "playwright",
    },
    exclude: ["main.js", "**/scripts/**", "**/node_modules/**", "**/dist/**"],
  },
});
