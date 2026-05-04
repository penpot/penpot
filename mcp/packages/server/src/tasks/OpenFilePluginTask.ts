import { OpenFileTaskParams, PluginTaskResult } from "@penpot/mcp-common";
import { PluginTask } from "../PluginTask";

/**
 * Opens another team file in the workspace router (inside the MCP plugin iframe).
 */
export class OpenFilePluginTask extends PluginTask<OpenFileTaskParams, PluginTaskResult<Record<string, never>>> {
    constructor(params: OpenFileTaskParams) {
        super("openFile", params);
    }
}
