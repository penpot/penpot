// Bundle the npm libraries of the `test-wasm` build (its external index)
// and copy its page, which replays generated component programs in the
// browser with the WASM engine on: `/test-wasm.html?programs=<file>.js`.
import * as esbuild from "esbuild";
import { copyFile, mkdir } from "node:fs/promises";

await esbuild.build({
  entryPoints: ["target/test-wasm-index.js"],
  bundle: true,
  format: "esm",
  banner: {
    js: '"use strict";\nvar global = globalThis;',
  },
  outfile: "resources/public/js/test-wasm/libs.js",
});

await mkdir("resources/public", { recursive: true });
await copyFile(
  "resources/test-wasm/index.html",
  "resources/public/test-wasm.html",
);
