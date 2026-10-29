import { build } from "esbuild";

await build({
  entryPoints: ["src/index.ts", "src/svg-worker.ts"],
  bundle: true,
  platform: "node",
  target: "node24",
  format: "esm",
  outdir: "dist",
  external: ["sharp", "ioredis", "dompurify", "jsdom", "pino", "pino-pretty", "pino-loki"],
  banner: {
    js: `
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
`,
  },
});
