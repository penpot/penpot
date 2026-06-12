import { ClientInstaller, InstallOptions, InstallResult } from "../types";
import { expandHome, readJsonFile, writeJsonFile, setNested, deleteNested, backupIfExists } from "../configWriter";

const CONFIG_PATH = "~/.gemini/antigravity/mcp_config.json";

export const antigravity: ClientInstaller = {
    id: "antigravity",
    label: "Antigravity",
    describe() {
        return "Antigravity. Writes user-level config at ~/.gemini/antigravity/mcp_config.json (mcpServers).";
    },
    configPath() {
        return expandHome(CONFIG_PATH);
    },
    snippet(opts: InstallOptions) {
        return { url: opts.serverUrl };
    },
    async install(opts: InstallOptions): Promise<InstallResult> {
        const file = this.configPath();
        const entry = this.snippet(opts);
        if (opts.dryRun) {
            return { client: this.id, path: file, written: false, skipped: "dry-run", snippet: entry };
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
