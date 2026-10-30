/** Metadata identifying one connected Penpot plugin session. */
export interface PenpotSession {
    sessionId: string;
    fileId: string | null;
    fileName: string | null;
    workspaceState?: "none" | "loading" | "ready";
}

/** First message sent by the plugin on each new WebSocket connection. */
export interface PluginConnectionInit {
    type: "initialize";
    session: PenpotSession;
}

/**
 * Result of a plugin task execution.
 *
 * Contains the outcome status of a task and any additional result data.
 */
export interface PluginTaskResult<T> {
    /**
     * Optional result data from the task execution.
     */
    data?: T;
}

/**
 * Request message sent from server to plugin.
 *
 * Contains a unique identifier, task name, and parameters for execution.
 */
export interface PluginTaskRequest {
    /**
     * Unique identifier for request/response correlation.
     */
    id: string;

    /**
     * The name of the task to execute.
     */
    task: string;

    /**
     * The parameters for task execution.
     */
    params: any;
}

/**
 * Response message sent from plugin back to server.
 *
 * Contains the original request ID and the execution result.
 */
export interface PluginTaskResponse<T> {
    /**
     * Unique identifier matching the original request.
     */
    id: string;

    /**
     * Whether the task completed successfully.
     */
    success: boolean;

    /**
     * Optional error message if the task failed.
     */
    error?: string;

    /**
     * The result of the task execution.
     */
    data?: T;
}

/**
 * Parameters for the executeCode task.
 */
export interface ExecuteCodeTaskParams {
    /**
     * The JavaScript code to be executed.
     */
    code: string;
}

/**
 * Result data for the executeCode task.
 */
export interface ExecuteCodeTaskResultData<T> {
    /**
     * The result of the executed code, if any.
     */
    result: T;

    /**
     * Captured console output during code execution.
     */
    log: string;
}

/**
 * Status of the Penpot MCP plugin connection, as reported to the Penpot app.
 */
export type McpConnectionStatus = "idle" | "connecting" | "connected" | "disconnected" | "error";

/**
 * Message posted by a browser extension's content script to ask the Penpot page for its MCP session.
 */
export interface ExtensionHello {
    type: "penpot-mcp-extension:hello";
}

/**
 * Message posted by the Penpot page when it starts listening for an {@link ExtensionHello}.
 *
 * A content script that said hello before the page was listening says it again.
 */
export interface PageReady {
    type: "penpot-mcp:ready";
}

/**
 * Message posted by the Penpot page to announce its MCP session to a browser extension.
 *
 * The page sends it in reply to {@link ExtensionHello} and on every connection status change.
 */
export interface SessionAnnouncement {
    type: "penpot-mcp:session";
    /** the MCP session ID of the page, or null while it is not known. */
    sessionId: string | null;
    status: McpConnectionStatus;
}
