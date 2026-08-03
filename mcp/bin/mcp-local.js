#!/usr/bin/env node

// Entry point (bin) of the published @penpot/mcp package.
// This script is not intended to be run from a source checkout; there, use
// `pnpm run bootstrap` directly, as described in the README.

const { execSync } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

const packageRoot = path.resolve(__dirname, "..");
const pkg = require(path.join(packageRoot, "package.json"));

function pnpmVersion() {
  const match = (pkg.packageManager || "").match(/^pnpm@([^+]+)/);
  return match ? match[1] : "latest";
}

/**
 * Prepares the directory in which to bootstrap and run.
 *
 * The package contents are copied once per version to a runtime directory
 * owned by this launcher. The npm-owned package directory cannot be used;
 * we have to assume it is read-only (#9947).
 *
 * @returns {string} the directory to run the bootstrap in
 */
function prepareRuntimeRoot() {
  const cacheBase = process.env.XDG_CACHE_HOME || path.join(os.homedir(), ".cache");
  const runtimeRoot = path.join(cacheBase, "penpot-mcp", pkg.version);
  if (fs.existsSync(runtimeRoot)) {
    return runtimeRoot;
  }

  // copy to a temporary sibling first and rename, so an interrupted copy
  // cannot leave a partial runtime directory behind
  console.log(`Preparing runtime directory ${runtimeRoot} ...`);
  const tempRoot = `${runtimeRoot}.tmp-${process.pid}`;
  fs.rmSync(tempRoot, { recursive: true, force: true });
  fs.cpSync(packageRoot, tempRoot, {
    recursive: true,
    filter: (src) => path.basename(src) !== "node_modules",
  });
  try {
    fs.renameSync(tempRoot, runtimeRoot);
  } catch (error) {
    fs.rmSync(tempRoot, { recursive: true, force: true });
    if (!fs.existsSync(runtimeRoot)) {
      throw error;
    }
    // a concurrent launch created the runtime directory in the meantime; use it
  }
  return runtimeRoot;
}

// check arguments
const cliArgs = process.argv.slice(2);
if (cliArgs[0] === "client-setup") {
  // when invoked as `penpot-mcp client-setup [...]`, configure an MCP client
  // instead of bootstrapping/starting the server. This must run before the
  // runtime-directory copy and bootstrap, neither of which client setup needs.
  try {
    require("./client-setup.js").runClientSetup(cliArgs.slice(1));
    process.exit(0);
  } catch (error) {
    process.exit(error.status ?? 1);
  }
}
else {
  // regular case: run bootstrap, building and starting the servers

  // get runtime root
  const root = prepareRuntimeRoot();

  // pnpm-lock.yaml is hard-excluded by npm pack; it is shipped as pnpm-lock.dist.yaml
  // and restored here before bootstrap runs.
  const distLock = path.join(root, "pnpm-lock.dist.yaml");
  const lock = path.join(root, "pnpm-lock.yaml");
  if (fs.existsSync(distLock)) {
    fs.copyFileSync(distLock, lock);
  }

  // run the bootstrap command
  try {
    execSync(`npx -y pnpm@${pnpmVersion()} run bootstrap`, { cwd: root, stdio: "inherit" });
  } catch (error) {
    process.exit(error.status ?? 1);
  }
}
