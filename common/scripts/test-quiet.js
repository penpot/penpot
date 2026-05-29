import { spawnSync } from "node:child_process";

const progress = (msg) => process.stderr.write(`${msg}\n`);

progress("Building test bundle...");
const build = spawnSync("pnpm", ["run", "build:test"], {
  stdio: ["ignore", "pipe", "pipe"],
  maxBuffer: 64 * 1024 * 1024,
});

if (build.status !== 0) {
  progress("Building test bundle failed");
  if (build.stdout?.length) process.stdout.write(build.stdout);
  if (build.stderr?.length) process.stderr.write(build.stderr);
  process.exit(build.status ?? 1);
}

progress("Running tests...");
const result = spawnSync(
  "node",
  ["target/tests/test.js", ...process.argv.slice(2)],
  { stdio: "inherit" },
);

process.exit(result.status ?? 1);
