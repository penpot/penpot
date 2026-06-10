import path from "node:path";
import { ClientInstaller, InstallOptions, InstallResult } from "../types";
import { readJsonFile, writeJsonFile, setNested, deleteNested, backupIfExists } from "../configWriter";

function configPath(): string {
    const home = process.env.HOME || process.env.USERPROFILE || "";
    const rel = path.join("User", "globalStorage", "saoudrizwan.claude-dev", "settings", "cline_mcp_settings.json");
    if (process.platform === "darwin") {
        return path.join(home, "Library", "Application Support", "Code", rel);
    }
    if (process.platform === "win32") {
        const appData = process.env.APPDATA || path.join(home, "AppData", "Roaming");
        return path.join(appData, "Code", rel);
    }
    return path.join(home, ".config", "Code", rel);
}

export const cline: ClientInstaller = {
    id: "cline",
    label: "Cline (VSCode)",
    describe() {
        return "Cline VSCode extension. Mutates cline_mcp_settings.json in VSCode global storage.";
    },
    configPath() {
        return configPath();
    },
    snippet(opts: InstallOptions) {
        return {
            url: opts.serverUrl,
            disabled: false,
            autoApprove: [] as string[],
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
