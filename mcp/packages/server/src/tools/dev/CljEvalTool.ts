import { z } from "zod";
import { Tool } from "../../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../../ToolResponse";
import { TextResponse } from "../../ToolResponse";
import type { PenpotMcpServer } from "../../PenpotMcpServer";
import { NREPL_EVAL_SCRIPT, REPO_ROOT, ScriptRunner, TEST_REPL_SCRIPT } from "./runScript";

/**
 * Arguments for the CljEvalTool.
 */
export class CljEvalArgs {
    static schema = {
        code: z.string().min(1, "Code cannot be empty").describe("The Clojure code to evaluate."),
        target: z
            .enum(["test", "backend"])
            .optional()
            .describe(
                "`test` (default) evaluates in the warm test JVM: test classpath, CI flags, " +
                    "test database. `backend` evaluates in the live dev backend."
            ),
    };

    /**
     * The Clojure code to evaluate.
     */
    code!: string;

    /**
     * The JVM to evaluate in: `test` (default) or `backend`.
     */
    target?: "test" | "backend";
}

/**
 * Evaluates Clojure code in a warm backend JVM.
 *
 * The tool shells out to the scripts that own the JVMs and reports the exit
 * status with the captured output. It keeps no connection of its own.
 */
export class CljEvalTool extends Tool<CljEvalArgs> {
    private readonly runner = new ScriptRunner(REPO_ROOT);

    /**
     * @param mcpServer - the MCP server instance
     */
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CljEvalArgs.schema);
    }

    public getToolName(): string {
        return "clj_eval";
    }

    public getToolDescription(): string {
        return (
            "Evaluates Clojure code in a warm backend JVM. `test` (default) uses the test JVM " +
            "(test classpath, CI flags, test database); `backend` uses the live dev backend. " +
            "Never call `(user/restart-all)`: the client gets no reply. Do not rely on defs " +
            "surviving a reload."
        );
    }

    protected async executeCore(args: CljEvalArgs): Promise<ToolResponse> {
        const result =
            (args.target ?? "test") === "backend"
                ? await this.runner.run(process.execPath, [NREPL_EVAL_SCRIPT, "--backend"], args.code)
                : await this.runner.run(TEST_REPL_SCRIPT, ["eval"], args.code);

        return new TextResponse(this.runner.format(result, { success: "OK", failure: "ERROR" }));
    }
}
