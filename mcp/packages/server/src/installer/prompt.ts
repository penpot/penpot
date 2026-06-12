import readline from "node:readline";
import { ClientInstaller } from "./types";

interface Choice {
    label: string;
    value: string;
}

/**
 * Renders an interactive arrow-key picker that lets the user choose one client
 * (or "all") from the supplied list. Returns the chosen client id, or "all".
 *
 * Falls back to throwing if stdin is not a TTY. Falls back to a plain readline
 * numeric prompt if raw-mode is unsupported (e.g. some CI shells).
 */
export async function pickClientInteractive(clients: ClientInstaller[], promptVerb: string): Promise<string> {
    if (!process.stdin.isTTY) {
        throw new Error(
            "Interactive picker requires a TTY. Pass --client <id|all> instead, or run from an interactive terminal."
        );
    }

    const choices: Choice[] = clients.map((c) => ({ label: `${c.label}  (${c.id})`, value: c.id }));

    if (typeof process.stdin.setRawMode !== "function") {
        return numericFallback(choices, promptVerb);
    }

    return arrowKeyPicker(choices, promptVerb);
}

function arrowKeyPicker(choices: Choice[], verb: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const stdin = process.stdin;
        const stdout = process.stdout;
        let cursor = 0;
        let rendered = 0;

        const cleanup = () => {
            stdin.removeListener("data", onData);
            try {
                stdin.setRawMode!(false);
            } catch {
                // ignore
            }
            stdin.pause();
            stdout.write(SHOW_CURSOR);
        };

        const render = (initial: boolean) => {
            if (!initial) {
                // move cursor up to top of previously rendered block, then clear from cursor down
                stdout.write(`\x1b[${rendered}A\x1b[J`);
            }
            const lines: string[] = [];
            lines.push(BOLD + `? ${capitalize(verb)} Penpot MCP for which client?` + RESET);
            lines.push(DIM + "  ↑/↓ to move · Enter to select · q to quit" + RESET);
            for (let i = 0; i < choices.length; i++) {
                const active = i === cursor;
                const pointer = active ? CYAN + "❯" + RESET : " ";
                const label = active ? CYAN + choices[i].label + RESET : choices[i].label;
                lines.push(`${pointer} ${label}`);
            }
            rendered = lines.length;
            stdout.write(lines.join("\n") + "\n");
        };

        const onData = (chunk: Buffer) => {
            const s = chunk.toString();
            if (s === KEY_UP) {
                cursor = (cursor - 1 + choices.length) % choices.length;
                render(false);
                return;
            }
            if (s === KEY_DOWN) {
                cursor = (cursor + 1) % choices.length;
                render(false);
                return;
            }
            if (s === KEY_HOME) {
                cursor = 0;
                render(false);
                return;
            }
            if (s === KEY_END) {
                cursor = choices.length - 1;
                render(false);
                return;
            }
            if (s === "\r" || s === "\n") {
                cleanup();
                stdout.write("\n");
                resolve(choices[cursor].value);
                return;
            }
            if (s === "q" || s === "Q" || s === KEY_ESC || s === KEY_CTRL_C) {
                cleanup();
                stdout.write("\n");
                reject(new Error("aborted by user"));
                return;
            }
            // numeric quick-select 1-9
            if (/^[1-9]$/.test(s)) {
                const idx = parseInt(s, 10) - 1;
                if (idx < choices.length) {
                    cursor = idx;
                    render(false);
                }
            }
        };

        stdout.write(HIDE_CURSOR);
        try {
            stdin.setRawMode!(true);
        } catch (err) {
            stdout.write(SHOW_CURSOR);
            reject(err);
            return;
        }
        stdin.resume();
        stdin.setEncoding("utf8");
        stdin.on("data", onData);
        render(true);
    });
}

function numericFallback(choices: Choice[], verb: string): Promise<string> {
    return new Promise((resolve, reject) => {
        console.log(`\nWhich client do you want to ${verb}?\n`);
        choices.forEach((c, i) => {
            console.log(`  ${String(i + 1).padStart(2)}. ${c.label}`);
        });
        console.log("   q. Quit\n");
        const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
        const ask = () => {
            rl.question("Enter choice [1]: ", (answer) => {
                const trimmed = answer.trim() || "1";
                if (trimmed.toLowerCase() === "q") {
                    rl.close();
                    reject(new Error("aborted by user"));
                    return;
                }
                const direct = choices.find((c) => c.value === trimmed);
                if (direct) {
                    rl.close();
                    resolve(direct.value);
                    return;
                }
                const idx = Number.parseInt(trimmed, 10);
                if (Number.isInteger(idx) && idx >= 1 && idx <= choices.length) {
                    rl.close();
                    resolve(choices[idx - 1].value);
                    return;
                }
                console.log(`  Invalid choice: ${trimmed}. Try again.`);
                ask();
            });
        };
        ask();
    });
}

const KEY_UP = "\x1b[A";
const KEY_DOWN = "\x1b[B";
const KEY_HOME = "\x1b[H";
const KEY_END = "\x1b[F";
const KEY_ESC = "\x1b";
const KEY_CTRL_C = "\x03";
const HIDE_CURSOR = "\x1b[?25l";
const SHOW_CURSOR = "\x1b[?25h";
const BOLD = "\x1b[1m";
const DIM = "\x1b[2m";
const CYAN = "\x1b[36m";
const RESET = "\x1b[0m";

function capitalize(s: string): string {
    return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}
