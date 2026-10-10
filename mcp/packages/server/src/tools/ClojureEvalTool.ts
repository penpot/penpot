import { z } from "zod";
import "reflect-metadata";
import { Tool, type ToolCallContext } from "../Tool";
import type { ToolResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import type { ClojureRuntime } from "../clojure/ClojureRuntime";
import type { Target } from "../clojure/verdicts";

/**
 * Arguments for the ClojureEvalTool.
 */
export class ClojureEvalArgs {
    static schema = {
        code: z.string().optional().describe("Clojure forms; each is evaluated and its value printed."),
        file: z
            .string()
            .optional()
            .describe(
                "Path of a Clojure file inside the repository to load: host path, or relative to the repository root."
            ),
        file_content: z
            .string()
            .optional()
            .describe("Text of a Clojure file to load, for a file outside the repository."),
        file_name: z.string().optional().describe("Name that errors in file_content cite."),
        target: z
            .enum(["test", "backend"])
            .optional()
            .describe('"test" (default): the isolated test JVM. "backend": the running backend system.'),
        ns: z.string().optional().describe("Namespace to evaluate in (default user)."),
        reload: z
            .boolean()
            .optional()
            .describe("Test target only: reload changed namespaces before evaluating (default true)."),
        timeout_s: z.number().positive().optional().describe("Seconds to wait for an answer (default 120)."),
    };

    code?: string;
    file?: string;
    file_content?: string;
    file_name?: string;
    target?: Target;
    ns?: string;
    reload?: boolean;
    timeout_s?: number;
}

/**
 * Evaluates Clojure in the persistent test JVM or in the running backend.
 */
export class ClojureEvalTool extends Tool<ClojureEvalArgs> {
    constructor(
        mcpServer: PenpotMcpServer,
        private readonly runtime: ClojureRuntime
    ) {
        super(mcpServer, ClojureEvalArgs.schema);
    }

    public getToolName(): string {
        return "clj_eval";
    }

    public getToolDescription(): string {
        const d = this.runtime.defaults;
        return [
            "Evaluate Clojure over nREPL in a devenv JVM; returns what the code prints and each form's value. " +
                "For backend Clojure and common .cljc code; frontend ClojureScript is cljs_repl.",
            "Pass exactly one of code, file, or file_content. code: forms, evaluated in turn; unbalanced delimiters are reported with their line and column, unevaluated. " +
                "file: a file inside the repository, loaded through nREPL's load-file, so errors cite its lines; keep scratch files under tmp/ at the repository root, which git ignores. " +
                "file_content and file_name: the same for a file outside the repository.",
            'target "test" (the default): the isolated test JVM on the backend test classpath; changed files reload first (reload: false skips that). ' +
                "A call of code nil starts the JVM (about 15 s) without doing anything else, so it can warm up while you read code. " +
                'target "backend": the running system, app.system/system, on the dev database every devenv shares, so avoid mutations. Nothing reloads: pass an edited source as file to hot-load it. ' +
                "In-JVM restarts such as (app.main/restart), (restart), and (repl/refresh) are refused.",
            `Write fully qualified names (app.db/exec!, not db/exec!): an alias goes stale after a reload. ns sets the namespace (default user); timeout_s the wait (default ${d.evalTimeoutS}). ` +
                "*1, *e, and bindings survive from one call to the next, unless two calls overlap. Output over 16000 characters is cut in the middle: bind a large value with def and print a slice.",
            "The first line is the verdict with the exit code: OK (0). THREW (1). RELOAD FAILED (2): a file did not load and nothing was evaluated. JVM DIED (3). " +
                "BUSY (4): a test run holds the test JVM. TIMEOUT (4): interrupted at timeout_s. CANCELLED (4). ERROR: the line says why.",
        ].join("\n\n");
    }

    protected async executeCore(args: ClojureEvalArgs, context: ToolCallContext): Promise<ToolResponse> {
        return this.runtime.evaluate(args, context);
    }
}
