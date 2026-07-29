import { spawnSync } from "node:child_process";

const BUILD_STEPS = [
  { label: "Building test bundle", cmd: "pnpm", args: ["run", "build:test"] },
];

const progress = (msg) => process.stderr.write(`${msg}\n`);

for (const step of BUILD_STEPS) {
  progress(`${step.label}...`);
  const result = spawnSync(step.cmd, step.args, {
    stdio: ["ignore", "pipe", "pipe"],
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.status !== 0) {
    progress(`${step.label} failed`);
    if (result.stdout?.length) process.stdout.write(result.stdout);
    if (result.stderr?.length) process.stderr.write(result.stderr);
    process.exit(result.status ?? 1);
  }
}

progress("Running tests...");
const result = spawnSync(
  "node",
  ["target/tests/test.js", ...process.argv.slice(2)],
  { stdio: "inherit" },
);
process.exit(result.status ?? 1);
