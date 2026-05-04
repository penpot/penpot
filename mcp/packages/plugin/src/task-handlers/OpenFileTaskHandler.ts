import type { OpenFileTaskParams } from "@penpot/mcp-common";
import type { Penpot } from "@penpot/plugin-types";
import { Task, TaskHandler } from "../TaskHandler";

type PenpotOrchestration = Penpot & {
    openFile(fileId: string, pageId?: string): void;
};

const penpotOrch = penpot as PenpotOrchestration;

/**
 * Handles openFile navigation tasks from the MCP server.
 */
export class OpenFileTaskHandler extends TaskHandler<OpenFileTaskParams> {
    readonly taskType = "openFile";

    async handle(task: Task<OpenFileTaskParams>): Promise<void> {
        try {
            penpotOrch.openFile(task.params.fileId, task.params.pageId);
            task.sendSuccess({});
        } catch (error: unknown) {
            const msg = error instanceof Error ? error.message : String(error);
            task.sendError(msg);
        }
    }
}
