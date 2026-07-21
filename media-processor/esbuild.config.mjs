import { build } from "esbuild";

await build({
  entryPoints: ["src/index.ts"],
  bundle: true,
  platform: "node",
  target: "node24",
  format: "esm",
  outfile: "dist/index.js",
  external: ["sharp"],
  banner: {
    js: `
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
`,
  },
});
