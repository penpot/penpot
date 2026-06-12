import fs from "node:fs";
import path from "node:path";

/**
 * Schema of the on-disk Penpot MCP user config.
 *
 * Stored as JSON at the path returned by {@link configFilePath}. The file is created
 * with `0600` permissions because it contains a Personal Access Token.
 */
export interface PenpotMcpUserConfig {
    /** Penpot Personal Access Token. Empty/undefined when not configured. */
    pat?: string;
    /** Base URL of the target Penpot instance. */
    baseUrl?: string;
}

const DEFAULT_BASE_URL = "https://design.penpot.app";

export function configDir(): string {
    const home = process.env.HOME || process.env.USERPROFILE || "";
    if (process.platform === "win32") {
        const appData = process.env.APPDATA || path.join(home, "AppData", "Roaming");
        return path.join(appData, "penpot-mcp");
    }
    const xdg = process.env.XDG_CONFIG_HOME;
    return path.join(xdg || path.join(home, ".config"), "penpot-mcp");
}

export function configFilePath(): string {
    return path.join(configDir(), "config.json");
}

export function readUserConfig(): PenpotMcpUserConfig | undefined {
    const file = configFilePath();
    if (!fs.existsSync(file)) {
        return undefined;
    }
    try {
        const raw = fs.readFileSync(file, "utf8");
        if (!raw.trim()) {
            return undefined;
        }
        return JSON.parse(raw) as PenpotMcpUserConfig;
    } catch {
        return undefined;
    }
}

export function writeUserConfig(cfg: PenpotMcpUserConfig): string {
    const dir = configDir();
    fs.mkdirSync(dir, { recursive: true });
    const file = configFilePath();
    const json = JSON.stringify(cfg, null, 2) + "\n";
    fs.writeFileSync(file, json, { encoding: "utf8", mode: 0o600 });
    // chmod again in case the file already existed with broader permissions
    try {
        fs.chmodSync(file, 0o600);
    } catch {
        // ignore on platforms that don't support chmod
    }
    return file;
}

export function clearUserConfig(): boolean {
    const file = configFilePath();
    if (!fs.existsSync(file)) {
        return false;
    }
    fs.unlinkSync(file);
    return true;
}

/**
 * Resolves the effective PAT for the current process: env var wins, file config falls back.
 */
export function resolvePat(): string | undefined {
    if (process.env.PENPOT_PAT) {
        return process.env.PENPOT_PAT;
    }
    return readUserConfig()?.pat;
}

/**
 * Resolves the effective base URL: env var wins, file config falls back, default last.
 */
export function resolveBaseUrl(): string {
    if (process.env.PENPOT_BASE_URL) {
        return process.env.PENPOT_BASE_URL;
    }
    return readUserConfig()?.baseUrl || DEFAULT_BASE_URL;
}

export function maskPat(pat: string | undefined): string {
    if (!pat) {
        return "<unset>";
    }
    if (pat.length <= 8) {
        return "*".repeat(pat.length);
    }
    return pat.slice(0, 4) + "…" + pat.slice(-4) + ` (length=${pat.length})`;
}
