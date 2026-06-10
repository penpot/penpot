import path from "node:path";
import { execFileSync } from "node:child_process";
import { ClientInstaller, InstallOptions, InstallResult } from "../types";
import { expandHome, readJsonFile, writeJsonFile, setNested, deleteNested, backupIfExists } from "../configWriter";

const CONFIG_PATH = "~/.claude.json";

export const claudeCode: ClientInstaller = {
    id: "claude-code",
    label: "Claude Code (CLI)",
    describe() {
        return "Claude Code CLI. Prefers `claude mcp add` when available; falls back to ~/.claude.json edit.";
    },
    configPath() {
        return expandHome(CONFIG_PATH);
    },
    snippet(opts: InstallOptions) {
        return {
            type: "http",
            url: opts.serverUrl,
        };
    },
    async install(opts: InstallOptions): Promise<InstallResult> {
        const file = this.configPath();
        const entry = this.snippet(opts);

        if (opts.dryRun) {
            return { client: this.id, path: file, written: false, skipped: "dry-run", snippet: entry };
        }

        const claudeCli = which("claude");
        if (claudeCli) {
            try {
                execFileSync(claudeCli, ["mcp", "remove", opts.entryName], { stdio: "ignore" });
            } catch {
                // ignore — entry probably did not exist
            }
            execFileSync(claudeCli, ["mcp", "add", opts.entryName, "-t", "http", opts.serverUrl], { stdio: "inherit" });
            return { client: this.id, path: "(via `claude mcp add`)", written: true, snippet: entry };
        }

        backupIfExists(file);
        const cfg: any = readJsonFile(file) ?? {};
        if (!opts.force && cfg.mcpServers?.[opts.entryName]) {
            return {
                client: this.id,
                path: file,
                written: false,
                skipped: `entry '${opts.entryName}' already exists; use --force`,
                snippet: entry,
            };
        }
        setNested(cfg, ["mcpServers", opts.entryName], entry);
        writeJsonFile(file, cfg);
        return { client: this.id, path: file, written: true, snippet: entry };
    },
    async uninstall(opts: InstallOptions): Promise<InstallResult> {
        const file = this.configPath();
        if (opts.dryRun) {
            return { client: this.id, path: file, written: false, skipped: "dry-run" };
        }
        const claudeCli = which("claude");
        if (claudeCli) {
            try {
                execFileSync(claudeCli, ["mcp", "remove", opts.entryName], { stdio: "inherit" });
                return { client: this.id, path: "(via `claude mcp remove`)", written: true };
            } catch (err) {
                return { client: this.id, path: "(via `claude mcp remove`)", written: false, error: String(err) };
            }
        }
        const cfg: any = readJsonFile(file);
        if (!cfg) {
            return { client: this.id, path: file, written: false, skipped: "no config file" };
        }
        const removed = deleteNested(cfg, ["mcpServers", opts.entryName]);
        if (!removed) {
            return { client: this.id, path: file, written: false, skipped: "entry not present" };
        }
        writeJsonFile(file, cfg);
        return { client: this.id, path: file, written: true };
    },
};

function which(cmd: string): string | undefined {
    const PATH = process.env.PATH || "";
    const sep = process.platform === "win32" ? ";" : ":";
    const exts = process.platform === "win32" ? (process.env.PATHEXT || ".EXE;.CMD;.BAT").split(";") : [""];
    for (const dir of PATH.split(sep)) {
        for (const ext of exts) {
            const candidate = path.join(dir, cmd + ext);
            try {
                const fs = require("node:fs");
                if (fs.existsSync(candidate)) {
                    return candidate;
                }
            } catch {
                // continue
            }
        }
    }
    return undefined;
}
