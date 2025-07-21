import * as esbuild from "esbuild";
import { readFile } from "node:fs/promises";

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
  format: "iife",
  banner: {
    js: '"use strict";',
  },
  outfile: "resources/public/js/libs.js",
  plugins: [fixReactVirtualized, rebuildNotify],
};

async function watch() {
  let ctx = await esbuild.context(config);
  return ctx.watch();
}

if (process.argv.includes("--watch")) {
  await watch();
} else {
  const localConfig = { ...config, minify: true };
  await esbuild.build(localConfig);
}
