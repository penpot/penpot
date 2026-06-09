/**
 * Base classes for plugin tasks that are dispatched to a Penpot plugin instance
 * over a WebSocket connection.
 *
 * A task defines a specific operation for the plugin to execute along with
 * strongly-typed parameters and provides request/response correlation.
 */
import { PluginTaskRequest, PluginTaskResult } from "@penpot/mcp-common";
import { randomUUID } from "crypto";

/**
 * Abstract base for plugin tasks, defining the parts that the plugin dispatch and
 * response-correlation machinery (`PluginBridge.sendPluginTask` /
 * `PluginBridge.handlePluginTaskResponse`) depend upon.
 *
 * The dispatch path only needs to serialize a task to a request and, upon receiving
 * the plugin's response, settle the task via `resolveWithResult`/`rejectWithError`.
 * What "settling" actually means is left to subclasses: a local task resolves an
 * in-process promise (see {@link PluginTask}), whereas a remote task forwards the
 * outcome elsewhere (see {@link RemotePluginTask}).
 *
 * @template TParams - The strongly-typed parameters for this task
 * @template TResult - The expected result type from task execution
 */
export abstract class AbstractPluginTask<TParams = any, TResult extends PluginTaskResult<any> = PluginTaskResult<any>> {
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
     * Creates a new plugin task instance.
     *
     * @param task - The name of the task to execute
     * @param params - The parameters for task execution
     */
    protected constructor(task: string, params: TParams) {
        this.id = randomUUID();
        this.task = task;
        this.params = params;
    }

    /**
     * Serializes the task to a request message for transmission to the plugin.
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

    /**
     * Settles the task successfully with the given result.
     *
     * Called by the response-correlation machinery when the plugin reports success
     * for the task with the matching ID.
     *
     * @param result - The task execution result
     */
    abstract resolveWithResult(result: TResult): void;

    /**
     * Settles the task unsuccessfully with the given error.
     *
     * Called by the response-correlation machinery when task execution fails
     * or times out.
     *
     * @param error - The error that occurred during task execution
     */
    abstract rejectWithError(error: Error): void;
}

/**
 * A locally-awaited plugin task.
 *
 * The task's outcome is exposed as an in-process promise (see {@link getResultPromise}),
 * which the caller awaits to obtain the result. This is the task type used by tools that
 * execute operations on the plugin and consume the result directly.
 *
 * @template TParams - The strongly-typed parameters for this task
 * @template TResult - The expected result type from task execution
 */
export class PluginTask<
    TParams = any,
    TResult extends PluginTaskResult<any> = PluginTaskResult<any>,
> extends AbstractPluginTask<TParams, TResult> {
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
     * Creates a new locally-awaited plugin task.
     *
     * @param task - The name of the task to execute
     * @param params - The parameters for task execution
     */
    constructor(task: string, params: TParams) {
        super(task, params);
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

    resolveWithResult(result: TResult): void {
        if (!this.resolveResult) {
            throw new Error("Result promise not initialized");
        }
        this.resolveResult(result);
    }

    rejectWithError(error: Error): void {
        if (!this.rejectResult) {
            throw new Error("Result promise not initialized");
        }
        this.rejectResult(error);
    }
}
