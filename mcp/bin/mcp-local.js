#!/usr/bin/env node

const { execSync } = require("child_process");
const path = require("path");

const root = path.resolve(__dirname, "..");

function run(command) {
  execSync(command, { cwd: root, stdio: "inherit" });
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
