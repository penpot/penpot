#!/usr/bin/env node

const { execSync, spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const pkg = require(path.join(root, "package.json"));
const serverRoot = path.join(root, "packages/server");
const pluginRoot = path.join(root, "packages/plugin");
const serverEntry = path.join(serverRoot, "dist/index.js");

function pnpmVersion() {
    const match = (pkg.packageManager || "").match(/^pnpm@([^+]+)/);
    return match ? match[1] : "latest";
}

function run(command) {
    execSync(command, { cwd: root, stdio: "inherit" });
}

function resolveViteEntry() {
    try {
        const vitePkg = require.resolve("vite/package.json", { paths: [root] });
        return path.join(path.dirname(vitePkg), "bin/vite.js");
    } catch {
        return undefined;
    }
}

function hasRuntimeArtifacts() {
    const viteEntry = resolveViteEntry();

    return [
        serverEntry,
        path.join(pluginRoot, "dist/index.html"),
        path.join(pluginRoot, "dist/manifest.json"),
        viteEntry || "",
    ].every((file) => fs.existsSync(file));
}

function startRuntime() {
    const viteEntry = resolveViteEntry();

    const processes = [
        spawn(process.execPath, [serverEntry], { cwd: serverRoot, stdio: "inherit" }),
        spawn(process.execPath, [viteEntry, "preview", "--config", "vite.config.ts"], {
            cwd: pluginRoot,
            stdio: "inherit",
        }),
    ];

    function stop(signal) {
        for (const child of processes) {
            child.kill(signal);
        }
    }

    process.on("SIGINT", () => stop("SIGINT"));
    process.on("SIGTERM", () => stop("SIGTERM"));

    for (const child of processes) {
        child.on("exit", (code, signal) => {
            stop(signal || "SIGTERM");
            process.exit(code ?? (signal ? 1 : 0));
        });
    }
}

if (hasRuntimeArtifacts() && !process.env.WS_URI) {
    startRuntime();
} else {
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
}
