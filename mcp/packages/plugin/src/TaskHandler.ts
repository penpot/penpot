/**
 * Represents a task received from the MCP server in the Penpot MCP plugin
 */
export class Task<TParams = any> {
    public isResponseSent: boolean = false;

    /**
     * @param requestId Unique identifier for the task request
     * @param taskType The type of the task to execute
     * @param params Task parameters/arguments
     */
    constructor(
        public requestId: string,
        public taskType: string,
        public params: TParams
    ) {}

    /**
     * Sends a task response back to the MCP server.
     */
    protected sendResponse(success: boolean, data: any = undefined, error: any = undefined): void {
        if (this.isResponseSent) {
            console.error("Response already sent for task:", this.requestId);
            return;
        }

        // create response object
        const response = {
            type: "task-response",
            // NOTE: This inner response schema also constructed in main.ts/sendTaskResponse.
            response: {
                id: this.requestId,
                success: success,
                data: data,
                error: error,
            },
        };

        // Send to main.ts which will forward to MCP server via WebSocket
        try {
            // TODO: Remove ts-ignore once Penpot types have been updated
            // @ts-ignore
            penpot.ui.sendMessage(response, true);
        } catch (sendError) {
            console.error("Failed to transfer task response for transmission to MCP server", sendError);
            if (success) {
                console.info("Original response was a success response, sending an error response instead.");
                // If we were trying to send a success response, send an error response instead
                let cause = sendError instanceof Error ? sendError.message : String(sendError);
                this.sendError(
                    "Unexpected error sending task response: " +
                        cause +
                        " If you are an LLM and tried to serialize a regular object (shape, token, etc.), " +
                        "tell the user to report this error, providing information on the request you made."
                );
            } else {
                console.info(
                    "Original response was an error response, abandoning attempt to send alternative response to avoid infinite error loop."
                );
            }
            return;
        }
        console.log("Sent task response:", response);
        this.isResponseSent = true;
    }

    public sendSuccess(data: any = undefined): void {
        this.sendResponse(true, data);
    }

    public sendError(error: string): void {
        this.sendResponse(false, undefined, error);
    }
}

/**
 * Abstract base class for task handlers in the Penpot MCP plugin.
 *
 * @template TParams - The type of parameters this handler expects
 */
export abstract class TaskHandler<TParams = any> {
    /** The task type this handler is responsible for */
    abstract readonly taskType: string;

    /**
     * Checks if this handler can process the given task.
     *
     * @param task - The task identifier to check
     * @returns True if this handler applies to the given task
     */
    isApplicableTo(task: Task): boolean {
        return this.taskType === task.taskType;
    }

    /**
     * Handles the task with the provided parameters.
     *
     * @param task - The task to be handled
     */
    abstract handle(task: Task<TParams>): Promise<void>;
}
