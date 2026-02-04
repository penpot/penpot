import { z } from "zod";
import "reflect-metadata";
import { TextResponse, ToolResponse } from "./ToolResponse";
import type { PenpotMcpServer, SessionContext } from "./PenpotMcpServer";
import { createLogger } from "./logger";

/**
 * An empty arguments class for tools that do not require any parameters.
 */
export class EmptyToolArgs {
    static schema = {};
}

/**
 * Base class for type-safe tools with automatic schema generation and validation.
 *
 * This class provides type safety through automatic validation and strongly-typed
 * protected methods. All tools should extend this class.
 *
 * @template TArgs - The strongly-typed arguments class for this tool
 */
export abstract class Tool<TArgs extends object> {
    private readonly logger = createLogger("Tool");

    protected constructor(
        protected mcpServer: PenpotMcpServer,
        private inputSchema: z.ZodRawShape
    ) {}

    /**
     * Executes the tool with automatic validation and type safety.
     *
     * This method handles the unknown args from the MCP protocol,
     * delegating to the type-safe implementation.
     */
    async execute(args: unknown): Promise<ToolResponse> {
        try {
            let argsInstance: TArgs = args as TArgs;
            this.logger.info("Executing tool: %s; arguments: %s", this.getToolName(), this.formatArgs(argsInstance));

            // execute the actual tool logic
            let result = await this.executeCore(argsInstance);

            this.logger.info("Tool execution completed: %s", this.getToolName());
            return result;
        } catch (error) {
            this.logger.error(error);
            return new TextResponse(`Tool execution failed: ${String(error)}`);
        }
    }

    /**
     * Formats tool arguments for readable logging.
     *
     * Multi-line strings are preserved with proper indentation.
     */
    protected formatArgs(args: TArgs): string {
        const formatted: string[] = [];

        for (const [key, value] of Object.entries(args)) {
            if (typeof value === "string" && value.includes("\n")) {
                // multi-line string - preserve formatting with indentation
                const indentedValue = value
                    .split("\n")
                    .map((line, index) => (index === 0 ? line : "    " + line))
                    .join("\n");
                formatted.push(`  ${key}: ${indentedValue}`);
            } else if (typeof value === "string") {
                // single-line string
                formatted.push(`  ${key}: "${value}"`);
            } else if (value === null || value === undefined) {
                formatted.push(`  ${key}: ${value}`);
            } else {
                // other types (numbers, booleans, objects, arrays)
                const stringified = JSON.stringify(value, null, 2);
                if (stringified.includes("\n")) {
                    // multi-line JSON - indent it
                    const indented = stringified
                        .split("\n")
                        .map((line, index) => (index === 0 ? line : "    " + line))
                        .join("\n");
                    formatted.push(`  ${key}: ${indented}`);
                } else {
                    formatted.push(`  ${key}: ${stringified}`);
                }
            }
        }

        return formatted.length > 0 ? "\n" + formatted.join("\n") : "{}";
    }

    /**
     * Retrieves the current session context.
     *
     * @returns The session context for the current request, or undefined if not in a request context
     */
    protected getSessionContext(): SessionContext | undefined {
        return this.mcpServer.getSessionContext();
    }

    public getInputSchema() {
        return this.inputSchema;
    }

    /**
     * Returns the tool's unique name.
     */
    public abstract getToolName(): string;

    /**
     * Returns the tool's description.
     */
    public abstract getToolDescription(): string;

    /**
     * Executes the tool's core logic.
     *
     * @param args - The (typed) tool arguments
     */
    protected abstract executeCore(args: TArgs): Promise<ToolResponse>;
}
