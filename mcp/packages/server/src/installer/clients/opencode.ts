import fs from "node:fs";
import { ClientInstaller, InstallOptions, InstallResult } from "../types";
import { expandHome, readJsonFile, writeJsonFile, setNested, deleteNested, backupIfExists } from "../configWriter";

/**
 * Candidate config file names recognised by OpenCode. The first existing path is
 * used; if neither is present, `opencode.jsonc` is created (matching the format
 * documented by OpenCode and supporting `//` comments).
 */
const CANDIDATE_PATHS = ["~/.config/opencode/opencode.jsonc", "~/.config/opencode/opencode.json"];

function resolveConfigPath(): string {
    for (const candidate of CANDIDATE_PATHS) {
        const expanded = expandHome(candidate);
        if (fs.existsSync(expanded)) {
            return expanded;
        }
    }
    // none of the candidates exists yet — default to the .jsonc variant
    return expandHome(CANDIDATE_PATHS[0]);
}

export const opencode: ClientInstaller = {
    id: "opencode",
    label: "OpenCode",
    describe() {
        return "OpenCode. Uses `mcp` top-level key with `type: remote`. Reads either opencode.jsonc or opencode.json.";
    },
    configPath() {
        return resolveConfigPath();
    },
    snippet(opts: InstallOptions) {
        return {
            type: "remote",
            url: opts.serverUrl,
            enabled: true,
        };
    },
    async install(opts: InstallOptions): Promise<InstallResult> {
        const file = this.configPath();
        const entry = this.snippet(opts);
        if (opts.dryRun) {
            return { client: this.id, path: file, written: false, skipped: "dry-run", snippet: entry };
        }
        backupIfExists(file);
        const cfg: any = readJsonFile(file) ?? {};
        if (!opts.force && cfg.mcp?.[opts.entryName]) {
            return {
                client: this.id,
                path: file,
                written: false,
                skipped: `entry '${opts.entryName}' already exists; use --force`,
                snippet: entry,
            };
        }
        setNested(cfg, ["mcp", opts.entryName], entry);
        writeJsonFile(file, cfg);
        return { client: this.id, path: file, written: true, snippet: entry };
    },
    async uninstall(opts: InstallOptions): Promise<InstallResult> {
        const file = this.configPath();
        if (opts.dryRun) {
            return { client: this.id, path: file, written: false, skipped: "dry-run" };
        }
        const cfg: any = readJsonFile(file);
        if (!cfg) {
            return { client: this.id, path: file, written: false, skipped: "no config file" };
        }
        const removed = deleteNested(cfg, ["mcp", opts.entryName]);
        if (!removed) {
            return { client: this.id, path: file, written: false, skipped: "entry not present" };
        }
        writeJsonFile(file, cfg);
        return { client: this.id, path: file, written: true };
    },
};
