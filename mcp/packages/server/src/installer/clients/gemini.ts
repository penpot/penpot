import path from "node:path";
import { execFileSync } from "node:child_process";
import { ClientInstaller, InstallOptions, InstallResult } from "../types";

export const gemini: ClientInstaller = {
    id: "gemini",
    label: "Gemini CLI",
    describe() {
        return "Gemini CLI. Delegates to `gemini mcp add` when the binary is on PATH.";
    },
    configPath() {
        return "(via `gemini mcp add`)";
    },
    snippet(opts: InstallOptions) {
        return {
            command: `gemini mcp add ${opts.entryName} --transport http ${opts.serverUrl}`,
        };
    },
    async install(opts: InstallOptions): Promise<InstallResult> {
        const entry = this.snippet(opts);
        if (opts.dryRun) {
            return { client: this.id, path: this.configPath(), written: false, skipped: "dry-run", snippet: entry };
        }
        const cli = which("gemini");
        if (!cli) {
            return {
                client: this.id,
                path: this.configPath(),
                written: false,
                skipped: "`gemini` CLI not found on PATH",
                snippet: entry,
            };
        }
        try {
            execFileSync(cli, ["mcp", "add", opts.entryName, "--transport", "http", opts.serverUrl], {
                stdio: "inherit",
            });
            return { client: this.id, path: this.configPath(), written: true, snippet: entry };
        } catch (err) {
            return { client: this.id, path: this.configPath(), written: false, error: String(err), snippet: entry };
        }
    },
    async uninstall(opts: InstallOptions): Promise<InstallResult> {
        if (opts.dryRun) {
            return { client: this.id, path: this.configPath(), written: false, skipped: "dry-run" };
        }
        const cli = which("gemini");
        if (!cli) {
            return {
                client: this.id,
                path: this.configPath(),
                written: false,
                skipped: "`gemini` CLI not found on PATH",
            };
        }
        try {
            execFileSync(cli, ["mcp", "remove", opts.entryName], { stdio: "inherit" });
            return { client: this.id, path: this.configPath(), written: true };
        } catch (err) {
            return { client: this.id, path: this.configPath(), written: false, error: String(err) };
        }
    },
};

function which(cmd: string): string | undefined {
    const PATH = process.env.PATH || "";
    const sep = process.platform === "win32" ? ";" : ":";
    const exts = process.platform === "win32" ? (process.env.PATHEXT || ".EXE;.CMD;.BAT").split(";") : [""];
    const fs = require("node:fs");
    for (const dir of PATH.split(sep)) {
        for (const ext of exts) {
            const candidate = path.join(dir, cmd + ext);
            try {
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
