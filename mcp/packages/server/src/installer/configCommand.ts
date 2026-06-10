import readline from "node:readline";
import {
    PenpotMcpUserConfig,
    configFilePath,
    readUserConfig,
    writeUserConfig,
    clearUserConfig,
    resolvePat,
    resolveBaseUrl,
    maskPat,
} from "./config";

const DEFAULT_BASE_URL = "https://design.penpot.app";

export type ConfigSubcommand = "set" | "show" | "clear" | "get-pat" | "get-base-url";

export interface ConfigCliArgs {
    sub: ConfigSubcommand;
    /** Non-interactive PAT override; valid only with `set`. */
    pat?: string;
    /** Non-interactive base URL override; valid only with `set`. */
    baseUrl?: string;
}

export function parseConfigArgs(argv: string[]): ConfigCliArgs {
    if (argv.length === 0) {
        throw new Error("Missing subcommand for `config`. Try `config set`, `config show`, or `config clear`.");
    }
    const sub = argv[0] as ConfigSubcommand;
    if (!["set", "show", "clear", "get-pat", "get-base-url"].includes(sub)) {
        throw new Error(`Unknown config subcommand: ${sub}. Valid: set, show, clear, get-pat, get-base-url`);
    }
    const out: ConfigCliArgs = { sub };
    for (let i = 1; i < argv.length; i++) {
        const a = argv[i];
        if (a === "--pat" && argv[i + 1]) {
            out.pat = argv[++i];
        } else if (a === "--base-url" && argv[i + 1]) {
            out.baseUrl = argv[++i];
        } else {
            throw new Error(`Unknown argument: ${a}`);
        }
    }
    if (out.sub !== "set" && (out.pat !== undefined || out.baseUrl !== undefined)) {
        throw new Error("`--pat` and `--base-url` are only valid with `config set`.");
    }
    return out;
}

export async function runConfig(args: ConfigCliArgs): Promise<number> {
    switch (args.sub) {
        case "set":
            return runConfigSet(args);
        case "show":
            return runConfigShow();
        case "clear":
            return runConfigClear();
        case "get-pat": {
            const v = resolvePat();
            if (v) {
                process.stdout.write(v);
                return 0;
            }
            console.error("No PAT configured.");
            return 1;
        }
        case "get-base-url": {
            process.stdout.write(resolveBaseUrl());
            return 0;
        }
    }
}

async function runConfigSet(args: ConfigCliArgs): Promise<number> {
    const existing = readUserConfig() ?? {};
    const interactive = process.stdin.isTTY && args.pat === undefined && args.baseUrl === undefined;

    let pat = args.pat;
    let baseUrl = args.baseUrl;

    if (interactive) {
        const patLabel = existing.pat ? `Enter PAT [keep current ${maskPat(existing.pat)}]: ` : "Enter PAT: ";
        const patInput = await promptMasked(patLabel);
        pat = patInput || existing.pat;
        if (!pat) {
            console.error("No PAT entered — aborting.");
            return 1;
        }

        const defaultUrl = existing.baseUrl || DEFAULT_BASE_URL;
        const baseUrlInput = await promptLine("Base URL: ", defaultUrl);
        baseUrl = baseUrlInput.trim() || defaultUrl;
    } else {
        if (pat === undefined) {
            pat = existing.pat;
        }
        if (baseUrl === undefined) {
            baseUrl = existing.baseUrl;
        }
    }

    if (!pat) {
        console.error("Refusing to write empty PAT. Provide --pat or run interactively and enter a value.");
        return 1;
    }

    const next: PenpotMcpUserConfig = { pat, baseUrl };
    const file = writeUserConfig(next);
    console.log(`Saved configuration to ${file} (mode 0600).`);
    console.log(`PAT       : ${maskPat(next.pat)}`);
    console.log(`Base URL  : ${next.baseUrl || DEFAULT_BASE_URL}`);
    return 0;
}

function runConfigShow(): number {
    const file = configFilePath();
    const cfg = readUserConfig();
    console.log(`Config file: ${file}${cfg ? "" : " (does not exist)"}`);
    console.log(`PAT (from env or file):      ${maskPat(resolvePat())}`);
    console.log(`Base URL (effective):        ${resolveBaseUrl()}`);
    if (process.env.PENPOT_PAT) {
        console.log("Note: PENPOT_PAT environment variable is set and overrides the config file.");
    }
    if (process.env.PENPOT_BASE_URL) {
        console.log("Note: PENPOT_BASE_URL environment variable is set and overrides the config file.");
    }
    return 0;
}

function runConfigClear(): number {
    const removed = clearUserConfig();
    if (removed) {
        console.log(`Removed ${configFilePath()}.`);
        return 0;
    }
    console.log(`No config file at ${configFilePath()}; nothing to remove.`);
    return 0;
}

function promptLine(prompt: string, preset?: string): Promise<string> {
    return new Promise((resolve) => {
        const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
        rl.question(prompt, (answer) => {
            rl.close();
            resolve(answer);
        });
        if (preset) {
            rl.write(preset);
        }
    });
}

/**
 * Prompts for a secret value, echoing `*` instead of the typed characters when stdin is
 * a raw-mode-capable TTY. Falls back to plain readline (visible input) otherwise.
 */
function promptMasked(prompt: string): Promise<string> {
    if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== "function") {
        return promptLine(prompt);
    }
    return new Promise((resolve) => {
        const stdin = process.stdin;
        const stdout = process.stdout;
        let buf = "";

        const cleanup = () => {
            stdin.removeListener("data", onData);
            stdin.setRawMode!(false);
            stdin.pause();
        };

        const onData = (chunk: Buffer) => {
            const s = chunk.toString("utf8");
            for (const ch of s) {
                if (ch === "\r" || ch === "\n") {
                    stdout.write("\n");
                    cleanup();
                    resolve(buf);
                    return;
                }
                if (ch === "\x03") {
                    // Ctrl+C — abort
                    stdout.write("\n");
                    cleanup();
                    process.exit(130);
                }
                if (ch === "\x7f" || ch === "\b") {
                    if (buf.length > 0) {
                        buf = buf.slice(0, -1);
                        stdout.write("\b \b");
                    }
                    continue;
                }
                // ignore other control characters
                if (ch < " ") {
                    continue;
                }
                buf += ch;
                stdout.write("*");
            }
        };

        stdout.write(prompt);
        stdin.setRawMode!(true);
        stdin.resume();
        stdin.setEncoding("utf8");
        stdin.on("data", onData);
    });
}
