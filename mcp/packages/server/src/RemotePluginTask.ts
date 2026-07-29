import { AbstractPluginTask } from "./PluginTask";
import { PluginTaskResult } from "@penpot/mcp-common";
import type { RedisBridge } from "./RedisBridge";

/**
 * A plugin task whose outcome is forwarded back to a remote requester via Redis,
 * rather than awaited in-process.
 *
 * This task type is used on the server instance that holds the plugin's WebSocket
 * connection when a task request arrives over Redis (published by another instance
 * that received the corresponding tool call). It is dispatched to the plugin through
 * the ordinary local dispatch path; when the plugin responds, the response-correlation
 * machinery settles this task, and the overridden `resolveWithResult`/`rejectWithError`
 * publish the outcome back onto the requester's Redis response channel.
 *
 * Note that this task has its own ID (used to correlate the local WebSocket dispatch),
 * distinct from the original requester's task ID, which keys the Redis response channel.
 *
 * It deliberately carries no result promise: settling the task *is* the side effect
 * of publishing to Redis, and nothing awaits it locally.
 */
export class RemotePluginTask extends AbstractPluginTask<any, PluginTaskResult<any>> {
    /**
     * Creates a task that forwards its outcome to a Redis response channel.
     *
     * @param task - The name of the task to execute (from the incoming request)
     * @param params - The parameters for task execution (from the incoming request)
     * @param redisBridge - The Redis bridge used to publish the outcome
     * @param originalTaskId - The ID of the original request, which keys the response
     *   channel the requesting instance is awaiting
     */
    constructor(
        task: string,
        params: any,
        private readonly redisBridge: RedisBridge,
        private readonly originalTaskId: string
    ) {
        super(task, params);
    }

    resolveWithResult(result: PluginTaskResult<any>): void {
        this.redisBridge.publishTaskResponse(this.originalTaskId, {
            id: this.originalTaskId,
            success: true,
            data: result.data,
        });
    }

    rejectWithError(error: Error): void {
        this.redisBridge.publishTaskResponse(this.originalTaskId, {
            id: this.originalTaskId,
            success: false,
            error: error.message,
        });
    }
}
