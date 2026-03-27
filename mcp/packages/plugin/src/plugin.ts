import { ExecuteCodeTaskHandler } from "./task-handlers/ExecuteCodeTaskHandler";
import { Task, TaskHandler } from "./TaskHandler";

/**
 * Extracts the major.minor.patch prefix from a version string.
 *
 * @param version - a version string starting with major.minor.patch
 * @returns the major.minor.patch prefix, or the original string if it does not match
 */
function extractVersionPrefix(version: string): string {
    const match = version.match(/^(\d+\.\d+\.\d+)/);
    return match ? match[1] : version;
}

mcp?.setMcpStatus("connecting");

/**
 * Registry of all available task handlers.
 */
const taskHandlers: TaskHandler[] = [new ExecuteCodeTaskHandler()];

// Open the plugin UI (main.ts)
penpot.ui.open("Penpot MCP Plugin", `?theme=${penpot.theme}`, {
    width: 236,
    height: 210,
    hidden: !!mcp,
} as any);

// Register message handlers
penpot.ui.onMessage<string | { id: string; type?: string; status?: string; task: string; params: any }>((message) => {
    if (typeof message === "object" && message.type === "ui-initialized") {
        // Check Penpot version compatibility
        const penpotVersionPrefix = penpot.version ? extractVersionPrefix(penpot.version) : "<2.15"; // pre-2.15 versions don't have version info
        const mcpVersionPrefix = extractVersionPrefix(PENPOT_MCP_VERSION);
        console.log(`Penpot version: ${penpotVersionPrefix}, MCP version: ${mcpVersionPrefix}`);
        if (penpotVersionPrefix !== mcpVersionPrefix) {
            penpot.ui.sendMessage({
                type: "version-mismatch",
                mcpVersion: mcpVersionPrefix,
                penpotVersion: penpotVersionPrefix,
            });
        }
        // Initiate connection to remote MCP server (if enabled)
        if (mcp) {
            penpot.ui.sendMessage({
                type: "start-server",
                url: mcp?.getServerUrl(),
                token: mcp?.getToken(),
            });
        }
    } else if (typeof message === "object" && message.type === "update-connection-status") {
        mcp?.setMcpStatus(message.status || "unknown");
    } else if (typeof message === "object" && message.task && message.id) {
        // Handle plugin tasks submitted by the MCP server
        handlePluginTaskRequest(message).catch((error) => {
            console.error("Error in handlePluginTaskRequest:", error);
        });
    }
});

/**
 * Handles plugin task requests received from the MCP server via WebSocket.
 *
 * @param request - The task request containing ID, task type and parameters
 */
async function handlePluginTaskRequest(request: { id: string; task: string; params: any }): Promise<void> {
    console.log("Executing plugin task:", request.task, request.params);
    const task = new Task(request.id, request.task, request.params);

    // Find the appropriate handler
    const handler = taskHandlers.find((h) => h.isApplicableTo(task));

    if (handler) {
        try {
            // Cast the params to the expected type and handle the task
            console.log("Processing task with handler:", handler);
            await handler.handle(task);

            // check whether a response was sent and send a generic success if not
            if (!task.isResponseSent) {
                console.warn("Handler did not send a response, sending generic success.");
                task.sendSuccess("Task completed without a specific response.");
            }

            console.log("Task handled successfully:", task);
        } catch (error) {
            console.error("Error handling task:", error);
            const errorMessage = error instanceof Error ? error.message : "Unknown error";
            task.sendError(`Error handling task: ${errorMessage}`);
        }
    } else {
        console.error("Unknown plugin task:", request.task);
        task.sendError(`Unknown task type: ${request.task}`);
    }
}

if (mcp) {
    mcp.on("disconnect", async () => {
        penpot.ui.sendMessage({
            type: "stop-server",
        });
    });
    mcp.on("connect", async () => {
        penpot.ui.sendMessage({
            type: "start-server",
            url: mcp?.getServerUrl(),
            token: mcp?.getToken(),
        });
    });
}

// Handle theme change in the iframe
penpot.on("themechange", (theme) => {
    penpot.ui.sendMessage({
        source: "penpot",
        type: "themechange",
        theme,
    });
});
