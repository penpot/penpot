#!/usr/bin/env node

const { execSync, spawnSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const pkg = require(path.join(root, "package.json"));

const SUBCOMMANDS = new Set(["install", "uninstall", "doctor", "help"]);
const HELP_FLAGS = new Set(["--help", "-h"]);
const SERVER_DIST = path.join(root, "packages", "server", "dist", "index.js");

function pnpmVersion() {
    const match = (pkg.packageManager || "").match(/^pnpm@([^+]+)/);
    return match ? match[1] : "latest";
}

function run(command) {
    execSync(command, { cwd: root, stdio: "inherit" });
}

function ensureLockFile() {
    // pnpm-lock.yaml is hard-excluded by npm pack; it is shipped as pnpm-lock.dist.yaml
    // and restored here before bootstrap runs.
    const distLock = path.join(root, "pnpm-lock.dist.yaml");
    const lock = path.join(root, "pnpm-lock.yaml");
    if (fs.existsSync(distLock)) {
        fs.copyFileSync(distLock, lock);
    }
}

function ensureServerBuilt() {
    if (fs.existsSync(SERVER_DIST)) {
        return;
    }
    ensureLockFile();
    run(`npx -y pnpm@${pnpmVersion()} install`);
    run(`npx -y pnpm@${pnpmVersion()} -r run build`);
}

function bootstrap() {
    ensureLockFile();
    run(`npx -y pnpm@${pnpmVersion()} run bootstrap`);
}

function forwardToDist(args) {
    ensureServerBuilt();
    const result = spawnSync(process.execPath, [SERVER_DIST, ...args], { stdio: "inherit", cwd: root });
    process.exit(result.status ?? 1);
}

const argv = process.argv.slice(2);
const first = argv[0];

try {
    if (first && HELP_FLAGS.has(first)) {
        forwardToDist(["help"]);
    } else if (first && SUBCOMMANDS.has(first)) {
        forwardToDist(argv);
    } else if (!first || first === "serve") {
        // default action: full bootstrap (install deps, build, start servers)
        bootstrap();
    } else {
        // unknown arg — pass through to bootstrap so existing flags (e.g. --multi-user) still work
        bootstrap();
    }
} catch (error) {
    process.exit(error.status ?? 1);
}
