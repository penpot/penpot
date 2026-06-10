import { ClientInstaller, ClientId, InstallOptions, InstallResult, ALL_CLIENT_IDS } from "./types";
import { pickClientInteractive } from "./prompt";
import { claudeCode } from "./clients/claudeCode";
import { claudeDesktop } from "./clients/claudeDesktop";
import { cursor } from "./clients/cursor";
import { windsurf } from "./clients/windsurf";
import { cline } from "./clients/cline";
import { opencode } from "./clients/opencode";
import { gemini } from "./clients/gemini";
import { codex } from "./clients/codex";
import { antigravity } from "./clients/antigravity";
import { antigravityCli } from "./clients/antigravityCli";
import { genericJson } from "./clients/genericJson";

const INSTALLERS: Record<ClientId, ClientInstaller> = {
    "claude-code": claudeCode,
    "claude-desktop": claudeDesktop,
    cursor,
    windsurf,
    cline,
    opencode,
    gemini,
    codex,
    antigravity,
    "antigravity-cli": antigravityCli,
    "generic-json": genericJson,
};

const DEFAULT_SERVER_URL = process.env.PENPOT_MCP_INSTALL_URL ?? "http://localhost:4401/mcp";
const DEFAULT_ENTRY_NAME = "penpot";

export interface CliArgs {
    command: "install" | "uninstall" | "doctor" | "help" | "config";
    client?: ClientId | "all";
    serverUrl?: string;
    entryName?: string;
    dryRun?: boolean;
    force?: boolean;
}

export function parseInstallerArgs(argv: string[]): CliArgs | undefined {
    if (argv.length === 0) {
        return undefined;
    }
    const command = argv[0];
    if (command !== "install" && command !== "uninstall" && command !== "doctor" && command !== "help") {
        return undefined;
    }
    if (command === "help" || command === "doctor") {
        const out: CliArgs = { command };
        for (let i = 1; i < argv.length; i++) {
            if (argv[i] === "--url" && argv[i + 1]) {
                out.serverUrl = argv[++i];
            }
        }
        return out;
    }

    const out: CliArgs = { command, dryRun: false, force: false };
    for (let i = 1; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--client" && argv[i + 1]) {
            const v = argv[++i];
            if (v !== "all" && !ALL_CLIENT_IDS.includes(v as ClientId)) {
                throw new Error(`Unknown client: ${v}. Supported: ${["all", ...ALL_CLIENT_IDS].join(", ")}`);
            }
            out.client = v as ClientId | "all";
        } else if (a === "--url" && argv[i + 1]) {
            out.serverUrl = argv[++i];
        } else if (a === "--name" && argv[i + 1]) {
            out.entryName = argv[++i];
        } else if (a === "--dry-run") {
            out.dryRun = true;
        } else if (a === "--force") {
            out.force = true;
        } else {
            throw new Error(`Unknown argument: ${a}`);
        }
    }
    // client may be left undefined here; caller will prompt interactively when stdin is a TTY
    return out;
}

export function listClients(): ClientInstaller[] {
    return ALL_CLIENT_IDS.map((id) => INSTALLERS[id]);
}

export function getInstaller(id: ClientId): ClientInstaller {
    return INSTALLERS[id];
}

async function resolveClient(args: CliArgs, verb: string): Promise<ClientId | "all"> {
    if (args.client) {
        return args.client;
    }
    const chosen = await pickClientInteractive(listClients(), verb);
    if (chosen !== "all" && !ALL_CLIENT_IDS.includes(chosen as ClientId)) {
        throw new Error(`Unknown client id from picker: ${chosen}`);
    }
    return chosen as ClientId | "all";
}

export async function runInstall(args: CliArgs): Promise<InstallResult[]> {
    const client = await resolveClient(args, "install");
    const opts: InstallOptions = {
        client,
        serverUrl: args.serverUrl ?? DEFAULT_SERVER_URL,
        entryName: args.entryName ?? DEFAULT_ENTRY_NAME,
        dryRun: !!args.dryRun,
        force: !!args.force,
    };
    const targets: ClientInstaller[] = opts.client === "all" ? listClients() : [INSTALLERS[opts.client]];
    const results: InstallResult[] = [];
    for (const t of targets) {
        try {
            results.push(await t.install({ ...opts, client: t.id }));
        } catch (err) {
            results.push({
                client: t.id,
                path: t.configPath(),
                written: false,
                error: err instanceof Error ? err.message : String(err),
            });
        }
    }
    return results;
}

export async function runUninstall(args: CliArgs): Promise<InstallResult[]> {
    const client = await resolveClient(args, "uninstall");
    const opts: InstallOptions = {
        client,
        serverUrl: args.serverUrl ?? DEFAULT_SERVER_URL,
        entryName: args.entryName ?? DEFAULT_ENTRY_NAME,
        dryRun: !!args.dryRun,
        force: !!args.force,
    };
    const targets: ClientInstaller[] = opts.client === "all" ? listClients() : [INSTALLERS[opts.client]];
    const results: InstallResult[] = [];
    for (const t of targets) {
        try {
            results.push(await t.uninstall({ ...opts, client: t.id }));
        } catch (err) {
            results.push({
                client: t.id,
                path: t.configPath(),
                written: false,
                error: err instanceof Error ? err.message : String(err),
            });
        }
    }
    return results;
}

export function printResults(results: InstallResult[]): void {
    for (const r of results) {
        const status = r.error ? "ERROR" : r.written ? "OK" : "SKIP";
        const detail = r.error ?? r.skipped ?? "";
        console.log(`[${status}] ${r.client.padEnd(16)} ${r.path}${detail ? `  (${detail})` : ""}`);
        if (r.snippet && (r.skipped === "dry-run" || r.error)) {
            console.log("       snippet:", JSON.stringify(r.snippet));
        }
    }
}

export function printHelp(): void {
    console.log(`Usage: penpot-mcp <command> [options]

Commands:
  serve [--multi-user]              Start MCP + plugin servers (default when no command given)
  install [--client <id|all>]       Register Penpot MCP with a client (interactive picker when --client is omitted)
  uninstall [--client <id|all>]     Remove Penpot MCP entry from a client (interactive picker when --client is omitted)
  config set [--pat X --base-url Y] Store Penpot PAT and base URL in the user config file (interactive when no flags)
  config show                       Print effective PAT (masked) and base URL
  config clear                      Delete the stored config file
  config get-pat | get-base-url     Print the resolved value (env var takes precedence over config file)
  doctor                            Health-check local MCP server + plugin connectivity
  help, --help, -h                  Show this message

install/uninstall options:
  --client <id|all>                 Target client. When omitted, an interactive picker is shown.
                                    Supported ids: ${["all", ...ALL_CLIENT_IDS].join(", ")}
  --url <url>                       MCP server URL (default: ${DEFAULT_SERVER_URL})
  --name <name>                     Entry name to register (default: ${DEFAULT_ENTRY_NAME})
  --dry-run                         Print the snippet that would be written, do not modify files
  --force                           Overwrite existing entry with the same name

Examples:
  penpot-mcp install                              # interactive picker
  penpot-mcp install --client claude-code
  penpot-mcp install --client all --dry-run
  penpot-mcp install --client claude-desktop --url http://localhost:4401/mcp --name penpot-dev
  penpot-mcp uninstall --client cursor
  penpot-mcp config set                           # interactive PAT + base URL
  penpot-mcp config set --base-url https://penpot.example.com
  penpot-mcp config show
  penpot-mcp doctor
`);
}
