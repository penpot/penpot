#!/usr/bin/env node

const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");

function run(command) {
  execSync(command, { cwd: root, stdio: "inherit" });
}

// pnpm-lock.yaml is hard-excluded by npm pack; it is shipped as pnpm-lock.dist.yaml
// and restored here before bootstrap runs.
const distLock = path.join(root, "pnpm-lock.dist.yaml");
const lock = path.join(root, "pnpm-lock.yaml");
if (fs.existsSync(distLock)) {
  fs.copyFileSync(distLock, lock);
}

try {
  run("corepack pnpm run bootstrap");
} catch (error) {
  if (error.code === "ENOENT") {
    console.error(
      "corepack is required but was not found. It ships with Node.js >= 16."
    );
    process.exit(1);
  }
  process.exit(error.status ?? 1);
}
