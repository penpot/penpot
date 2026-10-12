import { build } from "esbuild";

await build({
  entryPoints: ["src/index.ts", "src/svg-worker.ts"],
  bundle: true,
  platform: "node",
  target: "node24",
  format: "esm",
  outdir: "dist",
  external: ["sharp", "pino", "pino-pretty", "pino-loki", "dompurify", "jsdom"],
  banner: {
    js: `
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
`,
  },
});
