import { spawn } from "node:child_process";
import path from "node:path";

/**
 * Repository root, derived from the server package root.
 *
 * Assumes the process cwd is the server package root, the same assumption
 * `ConfigurationLoader` and `ImportPenpotFileTool` make.
 */
export const REPO_ROOT = path.resolve("../../..");

/** The warm test JVM wrapper: `test [ID ...]`, `eval [CODE]`, `start`, `stop`, `status`. */
export const TEST_REPL_SCRIPT = path.join(REPO_ROOT, "backend", "scripts", "test-repl");

/** The nREPL client used for one-off evaluations; `--backend` targets the live dev backend. */
export const NREPL_EVAL_SCRIPT = path.join(REPO_ROOT, "scripts", "nrepl-eval.mjs");

/**
 * Result of a script invocation.
 */
export interface ScriptResult {
    /** Exit status, or `null` when a signal killed the process. */
    exitCode: number | null;

    /** Everything the script wrote to stdout. */
    stdout: string;

    /** Everything the script wrote to stderr. */
    stderr: string;
}

/**
 * The first-line labels of a formatted script response.
 */
export interface ScriptStatusLabels {
    /** Label for exit status 0. */
    success: string;

    /** Label for any other exit status; the status follows in parentheses. */
    failure: string;
}

/**
 * Runs a script in the repository root and formats its result.
 *
 * The runner adds no timeout of its own: the scripts it calls bound themselves.
 */
export class ScriptRunner {
    /** Upper bound on a formatted response body; longer output keeps its head and tail. */
    static readonly MAX_OUTPUT_CHARS = 20_000;

    /**
     * @param repoRoot - the repository root used as the working directory of every script
     */
    constructor(private readonly repoRoot: string = REPO_ROOT) {}

    /**
     * Runs `command` with `args` without a shell, feeding `stdin` to the process.
     *
     * @param command - the executable to run
     * @param args - its arguments, passed verbatim
     * @param stdin - text written to the process stdin before it is closed
     * @returns the exit status and the captured output
     */
    public run(command: string, args: string[], stdin?: string): Promise<ScriptResult> {
        return new Promise((resolve, reject) => {
            const child = spawn(command, args, { cwd: this.repoRoot, shell: false });
            let stdout = "";
            let stderr = "";

            child.stdout.setEncoding("utf8");
            child.stderr.setEncoding("utf8");
            child.stdout.on("data", (chunk: string) => {
                stdout += chunk;
            });
            child.stderr.on("data", (chunk: string) => {
                stderr += chunk;
            });
            child.on("error", reject);
            child.on("close", (exitCode) => {
                resolve({ exitCode, stdout, stderr });
            });

            // close stdin unconditionally, or a script that reads it waits forever
            child.stdin.end(stdin ?? "");
        });
    }

    /**
     * Formats a result as a status line followed by the captured output.
     *
     * @param result - the script result to format
     * @param labels - the first-line labels for success and failure
     * @returns the response text
     */
    public format(result: ScriptResult, labels: ScriptStatusLabels): string {
        const status = result.exitCode === 0 ? labels.success : `${labels.failure} (exit ${result.exitCode ?? "none"})`;
        const body = truncateMiddle(joinOutput(result), ScriptRunner.MAX_OUTPUT_CHARS);

        return body.length > 0 ? `${status}\n${body}` : status;
    }
}

/**
 * Joins stdout and stderr into one body, keeping their order of appearance.
 */
function joinOutput(result: ScriptResult): string {
    return [result.stdout, result.stderr]
        .map((part) => part.trimEnd())
        .filter((part) => part.length > 0)
        .join("\n");
}

/**
 * Shortens `text` to at most `limit` characters, keeping its head and tail.
 *
 * Failures print last in clojure.test summaries and the report header first,
 * so both ends carry the information worth keeping.
 *
 * @param text - the text to shorten
 * @param limit - the maximum length of the returned text
 * @returns the text unchanged when it fits, otherwise head, omission marker, and tail
 */
export function truncateMiddle(text: string, limit: number): string {
    if (text.length <= limit) {
        return text;
    }

    const marker = `\n\n[... ${text.length - limit} characters omitted ...]\n\n`;
    const budget = Math.max(0, limit - marker.length);
    const headLength = Math.ceil(budget / 2);
    const tailLength = budget - headLength;
    const head = text.slice(0, headLength);
    const tail = tailLength > 0 ? text.slice(text.length - tailLength) : "";

    return head + marker + tail;
}
