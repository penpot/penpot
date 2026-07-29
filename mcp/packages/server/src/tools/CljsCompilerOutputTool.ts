import { Tool, EmptyToolArgs } from "../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { NreplClient } from "../NreplClient";

/**
 * Reports the compiler status of the shadow-cljs `:main` build.
 *
 * If the most recent build failed, returns the relevant fields of the failure data
 * (tag, message, resource name, line, column, etc.); otherwise returns `:ok`.
 */
export class CljsCompilerOutputTool extends Tool<EmptyToolArgs> {
    private static readonly STATUS_CODE =
        "(require (quote [shadow.cljs.devtools.api :as shadow])) " +
        "(let [fd (-> (shadow/get-worker :main) :state-ref deref :failure-data)] " +
        "(if fd (pr-str fd) :ok))";

    private readonly nreplClient: NreplClient;

    constructor(mcpServer: PenpotMcpServer, nreplClient: NreplClient) {
        super(mcpServer, EmptyToolArgs.schema);
        this.nreplClient = nreplClient;
    }

    public getToolName(): string {
        return "cljs_compiler_output";
    }

    public getToolDescription(): string {
        return (
            "Reports the status of the most recent shadow-cljs `:main` build. " +
            "Use this to diagnose compilation errors when needed. For syntax errors, " +
            "consider using the clj_check_parentheses tool on the relevant source files."
        );
    }

    protected async executeCore(_args: EmptyToolArgs): Promise<ToolResponse> {
        const result = await this.nreplClient.eval(CljsCompilerOutputTool.STATUS_CODE);

        // multiple top-level forms produce multiple values; the build status is the last one
        const status = result.values[result.values.length - 1] ?? "nil";

        const parts: string[] = [status];
        if (result.err) {
            parts.push(`stderr:\n${result.err}`);
        }

        return new TextResponse(parts.join("\n\n"));
    }
}
