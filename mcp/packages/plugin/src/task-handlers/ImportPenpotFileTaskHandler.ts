import type { ImportPenpotFileTaskParams } from "@penpot/mcp-common";
import type { Penpot } from "@penpot/plugin-types";
import { Task, TaskHandler } from "../TaskHandler";
import { PenpotUtils } from "../PenpotUtils";

type PenpotOrchestration = Penpot & {
    importPenpotFile(filename: string, data: Uint8Array): Promise<{ fileIds: string[] }>;
};

const penpotOrch = penpot as PenpotOrchestration;

/**
 * Handles importPenpotFile tasks from the MCP server.
 */
export class ImportPenpotFileTaskHandler extends TaskHandler<ImportPenpotFileTaskParams> {
    readonly taskType = "importPenpotFile";

    async handle(task: Task<ImportPenpotFileTaskParams>): Promise<void> {
        const { filename, base64 } = task.params;
        try {
            const bytes = PenpotUtils.base64ToByteArray(base64);
            const result = await penpotOrch.importPenpotFile(filename, bytes);
            task.sendSuccess(result);
        } catch (error: unknown) {
            const msg = error instanceof Error ? error.message : String(error);
            task.sendError(msg);
        }
    }
}
