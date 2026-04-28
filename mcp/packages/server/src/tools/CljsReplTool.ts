import { z } from "zod";
import { Tool } from "../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import { PenpotMcpServer } from "../PenpotMcpServer";
import { NreplClient } from "../NreplClient";

/**
 * Arguments for the CljsReplTool.
 */
export class CljsReplArgs {
    static schema = {
        code: z.string().min(1, "Code cannot be empty"),
    };

    /**
     * The ClojureScript code to evaluate in the frontend runtime.
     */
    code!: string;
}

/**
 * A ClojureScript REPL for the Penpot frontend runtime.
 *
 * This tool provides a persistent REPL session connected to the shadow-cljs nREPL server.
 * Definitions, requires, and other state are preserved across calls, enabling iterative
 * exploration and manipulation of the running Penpot application.
 */
export class CljsReplTool extends Tool<CljsReplArgs> {
    private readonly nreplClient: NreplClient;

    /**
     * Creates a new CljsReplTool instance.
     *
     * @param mcpServer - the MCP server instance
     * @param nreplClient - the nREPL client for communicating with shadow-cljs
     */
    constructor(mcpServer: PenpotMcpServer, nreplClient: NreplClient) {
        super(mcpServer, CljsReplArgs.schema);
        this.nreplClient = nreplClient;
    }

    public getToolName(): string {
        return "cljs_repl";
    }

    public getToolDescription(): string {
        return (
            "Persistent ClojureScript REPL in the Penpot frontend runtime (via shadow-cljs nREPL). " +
            "Definitions, requires, and state are preserved across calls — use it to build up helpers incrementally. " +
            "Multiple top-level expressions per call are supported; each produces a result line."
        );
    }

    protected async executeCore(args: CljsReplArgs): Promise<ToolResponse> {
        const result = await this.nreplClient.evalCljs(args.code);

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
