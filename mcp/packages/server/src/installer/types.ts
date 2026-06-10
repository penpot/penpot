export type ClientId =
    | "claude-code"
    | "claude-desktop"
    | "cursor"
    | "windsurf"
    | "cline"
    | "opencode"
    | "gemini"
    | "codex"
    | "antigravity"
    | "antigravity-cli"
    | "generic-json";

export const ALL_CLIENT_IDS: ClientId[] = [
    "claude-code",
    "claude-desktop",
    "cursor",
    "windsurf",
    "cline",
    "opencode",
    "gemini",
    "codex",
    "antigravity",
    "antigravity-cli",
    "generic-json",
];

export interface InstallOptions {
    client: ClientId | "all";
    serverUrl: string;
    entryName: string;
    dryRun: boolean;
    force: boolean;
}

export interface ClientInstaller {
    id: ClientId;
    label: string;
    describe(): string;
    configPath(): string;
    snippet(opts: InstallOptions): unknown;
    install(opts: InstallOptions): Promise<InstallResult>;
    uninstall(opts: InstallOptions): Promise<InstallResult>;
}

export interface InstallResult {
    client: ClientId;
    path: string;
    written: boolean;
    skipped?: string;
    error?: string;
    snippet?: unknown;
}
