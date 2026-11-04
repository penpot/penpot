import { rmSync } from "node:fs";
import { build } from "esbuild";

// The bundle is the only artifact: never leave a stale entry point behind.
rmSync("dist", { recursive: true, force: true });

await build({
  entryPoints: ["src/index.ts"],
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
