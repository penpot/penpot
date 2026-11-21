import * as esbuild from "esbuild";
import { readFile } from "node:fs/promises";
import { execSync } from "node:child_process";

/**
 * esbuild plugin to watch a directory recursively
 */
const watchExtraDirPlugin = {
  name: "watch-extra-dir",
  setup(build) {
    build.onLoad(
      { filter: /target\/index.js/, namespace: "file" },
      async (args) => {
        return {
          watchDirs: ["packages/ui/dist"],
        };
      },
    );
  },
};

const filter =
  /react-virtualized[/\\]dist[/\\]es[/\\]WindowScroller[/\\]utils[/\\]onScroll\.js$/;

const fixReactVirtualized = {
  name: "esbuild-plugin-react-virtualized",
  setup({ onLoad }) {
    onLoad({ filter }, async ({ path }) => {
      const code = await readFile(path, "utf8");
      const broken = `import { bpfrpt_proptype_WindowScroller } from "../WindowScroller.js";`;
      return { contents: code.replace(broken, "") };
    });
  },
};

const rebuildNotify = {
  name: "rebuild-notify",
  setup(build) {
    build.onEnd((result) => {
      // console.log(result);
      // [:main] Build completed. (1003 files, 1 compiled, 0 warnings, 9.06s)
      console.log(
        `[:libs] Build completed. (${result.errors.length} warnings, ${result.errors.length} errors)`,
      );
    });
  },
};

const config = {
  entryPoints: ["target/index.js"],
  bundle: true,
  format: "esm",
  banner: {
    js: '"use strict";\nvar global = globalThis;',
  },
  outfile: "resources/public/js/libs.js",
  plugins: [fixReactVirtualized, rebuildNotify, watchExtraDirPlugin],
};

// The libraries of the token resolution for the web worker, which loads
// them with `importScripts`
const workerConfig = {
  entryPoints: ["scripts/worker/token-libs.js"],
  bundle: true,
  format: "iife",
  // A worker has no `window`, so the libraries look at `process`
  banner: {
    js: 'var global = globalThis;\nvar process = globalThis.process || { env: {}, platform: "browser", cwd: function () { return "/"; } };',
  },
  outfile: "resources/public/js/worker/token-libs.js",
  plugins: [rebuildNotify],
};

async function watch() {
  let ctx = await esbuild.context(config);
  let workerCtx = await esbuild.context(workerConfig);
  return Promise.all([ctx.watch(), workerCtx.watch()]);
}

if (process.argv.includes("--watch")) {
  await watch();
} else {
  await esbuild.build({ ...config, minify: true });
  await esbuild.build({ ...workerConfig, minify: true });
}
