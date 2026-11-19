import { AsyncLocalStorage } from "async_hooks";
import type { ContentRollbackEvent } from "@penpot/mcp-common";
import { TextContent, ToolResponse } from "./ToolResponse";

/**
 * Collector of the rollbacks of rejected changes that plugin task responses report during one tool execution.
 *
 * The plugin reports a rollback with the first task response after it, so the rollback reaches the tool
 * execution that follows the one which made the changes. {@link withNotice} then puts a warning at the
 * top of that tool's response, so the LLM learns that some of its earlier changes are gone.
 */
export class RollbackNotices {
    /**
     * Maximum number of shape ids listed in a notice.
     */
    private static readonly MAX_LISTED_SHAPES = 50;

    private readonly storage = new AsyncLocalStorage<ContentRollbackEvent[]>();

    /**
     * Runs the given function, collecting the rollbacks recorded while it runs.
     *
     * @param fn - the function to run (typically a tool's core logic)
     * @returns the function's value and the collected rollbacks
     */
    async collect<T>(fn: () => Promise<T>): Promise<[T, ContentRollbackEvent[]]> {
        const rollbacks: ContentRollbackEvent[] = [];
        const value = await this.storage.run(rollbacks, fn);
        return [value, rollbacks];
    }

    /**
     * Records rollbacks reported by a plugin task response for the current tool execution.
     * Rollbacks reported outside of {@link collect} are dropped.
     *
     * @param rollbacks - the rollbacks the response carried, if any
     */
    record(rollbacks: ContentRollbackEvent[] | undefined): void {
        if (rollbacks && rollbacks.length > 0) {
            this.storage.getStore()?.push(...rollbacks);
        }
    }

    /**
     * Puts a rollback warning at the top of the given response.
     *
     * @param response - the tool response
     * @param rollbacks - the rollbacks collected while the tool ran
     * @returns the response, with the warning first if there were rollbacks
     */
    static withNotice(response: ToolResponse, rollbacks: ContentRollbackEvent[]): ToolResponse {
        if (rollbacks.length === 0) {
            return response;
        }
        // clients may join text items without a separator
        const notice = new TextContent(RollbackNotices.describe(rollbacks) + "\n\n");
        response.content = [notice, ...response.content];
        return response;
    }

    /**
     * Describes the given rollbacks for the LLM.
     *
     * @param rollbacks - the rollbacks to describe (at least one)
     */
    static describe(rollbacks: ContentRollbackEvent[]): string {
        const fileIds = new Set(rollbacks.flatMap((r) => r.fileIds));
        const pageIds = new Set(rollbacks.flatMap((r) => r.pageIds));
        const shapeIds = [...new Set(rollbacks.flatMap((r) => r.shapeIds))];

        const listedShapes = shapeIds.slice(0, RollbackNotices.MAX_LISTED_SHAPES);
        const unlistedShapes = shapeIds.length - listedShapes.length;

        return (
            "WARNING: CHANGES WERE ROLLED BACK. Since your previous Penpot tool call, the Penpot backend rejected " +
            `some changes ${rollbacks.length > 1 ? `(${rollbacks.length} times) ` : ""}and the workspace undid them, ` +
            "together with the rest of their undo steps and any later change to the same file. " +
            "Changes you made in earlier calls may be gone, even though those calls succeeded, and the tool call " +
            "that returned this warning may have run on the reverted data.\n" +
            "Before you continue, read the affected pages and shapes again, check that they hold what you expect, " +
            "and redo what is missing. Do not rely on shape references or data you kept from before.\n" +
            `Files: ${[...fileIds].join(", ") || "none"}\n` +
            `Pages: ${[...pageIds].join(", ") || "none"}\n` +
            `Shapes: ${listedShapes.join(", ") || "none"}` +
            (unlistedShapes > 0 ? ` (and ${unlistedShapes} more)` : "")
        );
    }
}
