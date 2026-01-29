/**
 * Base class for plugin tasks that are sent over WebSocket.
 *
 * Each task defines a specific operation for the plugin to execute
 * along with strongly-typed parameters.
 *
 * @template TParams - The strongly-typed parameters for this task
 */
import { PluginTaskRequest, PluginTaskResult } from "@penpot/mcp-common";
import { randomUUID } from "crypto";

/**
 * Base class for plugin tasks that are sent over WebSocket.
 *
 * Each task defines a specific operation for the plugin to execute
 * along with strongly-typed parameters and request/response correlation.
 *
 * @template TParams - The strongly-typed parameters for this task
 * @template TResult - The expected result type from task execution
 */
export abstract class PluginTask<TParams = any, TResult extends PluginTaskResult<any> = PluginTaskResult<any>> {
    /**
     * Unique identifier for request/response correlation.
     */
    public readonly id: string;

    /**
     * The name of the task to execute on the plugin side.
     */
    public readonly task: string;

    /**
     * The parameters for this task execution.
     */
    public readonly params: TParams;

    /**
     * Promise that resolves when the task execution completes.
     */
    private readonly result: Promise<TResult>;

    /**
     * Resolver function for the result promise.
     */
    private resolveResult?: (result: TResult) => void;

    /**
     * Rejector function for the result promise.
     */
    private rejectResult?: (error: Error) => void;

    /**
     * Creates a new plugin task instance.
     *
     * @param task - The name of the task to execute
     * @param params - The parameters for task execution
     */
    constructor(task: string, params: TParams) {
        this.id = randomUUID();
        this.task = task;
        this.params = params;
        this.result = new Promise<TResult>((resolve, reject) => {
            this.resolveResult = resolve;
            this.rejectResult = reject;
        });
    }

    /**
     * Gets the result promise for this task.
     *
     * @returns Promise that resolves when the task execution completes
     */
    getResultPromise(): Promise<TResult> {
        if (!this.result) {
            throw new Error("Result promise not initialized");
        }
        return this.result;
    }

    /**
     * Resolves the task with the given result.
     *
     * This method should be called when a task response is received
     * from the plugin with matching ID.
     *
     * @param result - The task execution result
     */
    resolveWithResult(result: TResult): void {
        if (!this.resolveResult) {
            throw new Error("Result promise not initialized");
        }
        this.resolveResult(result);
    }

    /**
     * Rejects the task with the given error.
     *
     * This method should be called when task execution fails
     * or times out.
     *
     * @param error - The error that occurred during task execution
     */
    rejectWithError(error: Error): void {
        if (!this.rejectResult) {
            throw new Error("Result promise not initialized");
        }
        this.rejectResult(error);
    }

    /**
     * Serializes the task to a request message for WebSocket transmission.
     *
     * @returns The request message containing ID, task name, and parameters
     */
    toRequest(): PluginTaskRequest {
        return {
            id: this.id,
            task: this.task,
            params: this.params,
        };
    }
}
