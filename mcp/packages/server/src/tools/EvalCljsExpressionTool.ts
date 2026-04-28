import { z } from "zod";
import { Tool } from "../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { NreplClient } from "../NreplClient";

/**
 * Arguments for the EvalCljsExpressionTool.
 */
export class EvalCljsExpressionArgs {
    static schema = {
        expression: z.string().min(1, "Expression cannot be empty"),
    };

    /**
     * The ClojureScript expression to evaluate in the frontend runtime.
     */
    expression!: string;
}

/**
 * Tool for evaluating ClojureScript expressions in the Penpot frontend runtime.
 *
 * This tool connects to the shadow-cljs nREPL server and evaluates the given
 * ClojureScript expression in the context of the running browser application,
 * providing direct access to the frontend application state and APIs.
 */
export class EvalCljsExpressionTool extends Tool<EvalCljsExpressionArgs> {
    private readonly nreplClient: NreplClient;

    /**
     * Creates a new EvalCljsExpressionTool instance.
     *
     * @param mcpServer - the MCP server instance
     * @param nreplClient - the nREPL client for communicating with shadow-cljs
     */
    constructor(mcpServer: PenpotMcpServer, nreplClient: NreplClient) {
        super(mcpServer, EvalCljsExpressionArgs.schema);
        this.nreplClient = nreplClient;
    }

    public getToolName(): string {
        return "eval_cljs_expression";
    }

    public getToolDescription(): string {
        return (
            "Evaluates a ClojureScript expression in the Penpot frontend runtime via the shadow-cljs nREPL server. " +
            "The expression is evaluated in the browser context, providing access to the application state and ClojureScript APIs."
        );
    }

    protected async executeCore(args: EvalCljsExpressionArgs): Promise<ToolResponse> {
        const result = await this.nreplClient.evalCljs(args.expression);

        const parts: string[] = [];
        if (result.values.length > 0) {
            parts.push(result.values.join("\n"));
        }
        if (result.out) {
            parts.push(`stdout:\n${result.out}`);
        }
        if (result.err) {
            parts.push(`stderr:\n${result.err}`);
        }
        if (parts.length === 0) {
            parts.push("nil");
        }

        return new TextResponse(parts.join("\n\n"));
    }
}
