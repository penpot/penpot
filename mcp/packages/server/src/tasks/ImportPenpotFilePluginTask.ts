import { ImportPenpotFileTaskParams, PluginTaskResult } from "@penpot/mcp-common";
import { PluginTask } from "../PluginTask";

/**
 * Imports a `.penpot` blob into the current team project inside Penpot (via the MCP plugin bridge).
 */
export class ImportPenpotFilePluginTask extends PluginTask<
    ImportPenpotFileTaskParams,
    PluginTaskResult<{ fileIds?: string[] }>
> {
    constructor(params: ImportPenpotFileTaskParams) {
        super("importPenpotFile", params);
    }
}
