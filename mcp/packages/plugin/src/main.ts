import "./style.css";

// get the current theme from the URL
const searchParams = new URLSearchParams(window.location.search);
document.body.dataset.theme = searchParams.get("theme") ?? "light";

// Determine whether multi-user mode is enabled based on URL parameters
const isMultiUserMode = searchParams.get("multiUser") === "true";
console.log("Penpot MCP multi-user mode:", isMultiUserMode);

// WebSocket connection management
let ws: WebSocket | null = null;
const statusElement = document.getElementById("connection-status");

/**
 * Updates the connection status display element.
 *
 * @param status - the base status text to display
 * @param isConnectedState - whether the connection is in a connected state (affects color)
 * @param message - optional additional message to append to the status
 */
function updateConnectionStatus(status: string, isConnectedState: boolean, message?: string): void {
    if (statusElement) {
        const displayText = message ? `${status}: ${message}` : status;
        statusElement.textContent = displayText;
        statusElement.style.color = isConnectedState ? "var(--accent-primary)" : "var(--error-700)";
    }
}

/**
 * Sends a task response back to the MCP server via WebSocket.
 *
 * @param response - The response containing task ID and result
 */
function sendTaskResponse(response: any): void {
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(response));
        console.log("Sent response to MCP server:", response);
    } else {
        console.error("WebSocket not connected, cannot send response");
    }
}

/**
 * Establishes a WebSocket connection to the MCP server.
 */
function connectToMcpServer(): void {
    if (ws?.readyState === WebSocket.OPEN) {
        updateConnectionStatus("Already connected", true);
        return;
    }

    try {
        let wsUrl = PENPOT_MCP_WEBSOCKET_URL;
        if (isMultiUserMode) {
            // TODO obtain proper userToken from penpot
            const userToken = "dummyToken";
            wsUrl += `?userToken=${encodeURIComponent(userToken)}`;
        }
        ws = new WebSocket(wsUrl);
        updateConnectionStatus("Connecting...", false);

        ws.onopen = () => {
            console.log("Connected to MCP server");
            updateConnectionStatus("Connected to MCP server", true);
        };

        ws.onmessage = (event) => {
            console.log("Received from MCP server:", event.data);
            try {
                const request = JSON.parse(event.data);
                // Forward the task request to the plugin for execution
                parent.postMessage(request, "*");
            } catch (error) {
                console.error("Failed to parse WebSocket message:", error);
            }
        };

        ws.onclose = (event: CloseEvent) => {
            console.log("Disconnected from MCP server");
            const message = event.reason || undefined;
            updateConnectionStatus("Disconnected", false, message);
            ws = null;
        };

        ws.onerror = (error) => {
            console.error("WebSocket error:", error);
            // note: WebSocket error events typically don't contain detailed error messages
            updateConnectionStatus("Connection error", false);
        };
    } catch (error) {
        console.error("Failed to connect to MCP server:", error);
        const message = error instanceof Error ? error.message : undefined;
        updateConnectionStatus("Connection failed", false, message);
    }
}

document.querySelector("[data-handler='connect-mcp']")?.addEventListener("click", () => {
    connectToMcpServer();
});

// Listen plugin.ts messages
window.addEventListener("message", (event) => {
    if (event.data.source === "penpot") {
        document.body.dataset.theme = event.data.theme;
    } else if (event.data.type === "task-response") {
        // Forward task response back to MCP server
        sendTaskResponse(event.data.response);
    }
});
