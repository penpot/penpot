import { PluginTask } from "../PluginTask";
import { ExecuteCodeTaskParams, ExecuteCodeTaskResultData, PluginTaskResult } from "@penpot/mcp-common";

/**
 * Task for executing JavaScript code in the plugin context.
 *
 * This task instructs the plugin to execute arbitrary JavaScript code
 * and return the result of execution.
 */
export class ExecuteCodePluginTask extends PluginTask<
    ExecuteCodeTaskParams,
    PluginTaskResult<ExecuteCodeTaskResultData<any>>
> {
    /**
     * Creates a new execute code task.
     *
     * @param params - The parameters containing the code to execute
     */
    constructor(params: ExecuteCodeTaskParams) {
        super("executeCode", params);
    }
}
