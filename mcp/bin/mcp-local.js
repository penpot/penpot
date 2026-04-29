#!/usr/bin/env node

const { execSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const pkg = require(path.join(root, "package.json"));

function pnpmVersion() {
  const match = (pkg.packageManager || "").match(/^pnpm@([^+]+)/);
  return match ? match[1] : "latest";
}

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
  run(`npx -y pnpm@${pnpmVersion()} run bootstrap`);
} catch (error) {
  process.exit(error.status ?? 1);
}
