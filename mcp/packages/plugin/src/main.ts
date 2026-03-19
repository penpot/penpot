import "./style.css";

// get the current theme from the URL
const searchParams = new URLSearchParams(window.location.search);
document.body.dataset.theme = searchParams.get("theme") ?? "light";

// WebSocket connection management
let ws: WebSocket | null = null;

const statusPill = document.getElementById("connection-status") as HTMLElement;
const statusText = document.getElementById("status-text") as HTMLElement;
const currentToolEl = document.getElementById("current-tool") as HTMLElement;
const executedCodeEl = document.getElementById("executed-code") as HTMLTextAreaElement;
const copyCodeBtn = document.getElementById("copy-code-btn") as HTMLButtonElement;
const connectBtn = document.getElementById("connect-btn") as HTMLButtonElement;
const disconnectBtn = document.getElementById("disconnect-btn") as HTMLButtonElement;

/**
 * Updates the status pill and button visibility based on connection state.
 *
 * @param code - the connection state code ("idle" | "connecting" | "connected" | "disconnected" | "error")
 * @param label - human-readable label to display inside the pill
 */
function updateConnectionStatus(code: string, label: string): void {
    if (statusPill) {
        statusPill.dataset.status = code;
    }
    if (statusText) {
        statusText.textContent = label;
    }

    const isConnected = code === "connected";
    if (connectBtn) connectBtn.hidden = isConnected;
    if (disconnectBtn) disconnectBtn.hidden = !isConnected;

    parent.postMessage(
        {
            type: "update-connection-status",
            status: code,
        },
        "*"
    );
}

/**
 * Updates the "Using tool" display with the currently executing tool name.
 *
 * @param toolName - the tool/task name to display, or null to reset to "---"
 */
function updateCurrentTool(toolName: string | null): void {
    if (currentToolEl) {
        currentToolEl.textContent = toolName ?? "---";
    }
    if (toolName === null) {
        updateExecutedCode(null);
    }
}

/**
 * Updates the executed code textarea with the last code run by the MCP server.
 *
 * @param code - the code string to display, or null to clear
 */
function updateExecutedCode(code: string | null): void {
    if (executedCodeEl) {
        executedCodeEl.value = code ?? "";
    }
    if (copyCodeBtn) {
        copyCodeBtn.disabled = !code;
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
function connectToMcpServer(baseUrl?: string, token?: string): void {
    if (ws?.readyState === WebSocket.OPEN) {
        updateConnectionStatus("connected", "Connected");
        return;
    }

    try {
        let wsUrl = baseUrl || PENPOT_MCP_WEBSOCKET_URL;
        if (token) {
            wsUrl += `?userToken=${encodeURIComponent(token)}`;
        }

        ws = new WebSocket(wsUrl);
        updateConnectionStatus("connecting", "Connecting...");

        ws.onopen = () => {
            console.log("Connected to MCP server");
            updateConnectionStatus("connected", "Connected");
        };

        ws.onmessage = (event) => {
            try {
                console.log("Received from MCP server:", event.data);
                const request = JSON.parse(event.data);
                // Track the last tool received from the MCP server
                if (request.task) {
                    updateCurrentTool(request.task);
                    updateExecutedCode(request.params?.code ?? null);
                }
                // Forward the task request to the plugin for execution
                parent.postMessage(request, "*");
            } catch (error) {
                console.error("Failed to parse WebSocket message:", error);
            }
        };

        ws.onclose = (event: CloseEvent) => {
            console.log("Disconnected from MCP server");
            const label = event.reason ? `Disconnected: ${event.reason}` : "Disconnected";
            updateConnectionStatus("disconnected", label);
            updateCurrentTool(null);
            ws = null;
        };

        ws.onerror = (error) => {
            console.error("WebSocket error:", error);
            updateConnectionStatus("error", "Connection error");
        };
    } catch (error) {
        console.error("Failed to connect to MCP server:", error);
        const reason = error instanceof Error ? error.message : undefined;
        const label = reason ? `Connection failed: ${reason}` : "Connection failed";
        updateConnectionStatus("error", label);
    }
}

copyCodeBtn?.addEventListener("click", () => {
    const code = executedCodeEl?.value;
    if (!code) return;

    navigator.clipboard.writeText(code).then(() => {
        copyCodeBtn.classList.add("copied");
        setTimeout(() => copyCodeBtn.classList.remove("copied"), 1500);
    });
});

connectBtn?.addEventListener("click", () => {
    connectToMcpServer();
});

disconnectBtn?.addEventListener("click", () => {
    ws?.close();
});

// Listen plugin.ts messages
window.addEventListener("message", (event) => {
    if (event.data.type === "start-server") {
        connectToMcpServer(event.data.url, event.data.token);
    }
    if (event.data.type === "stop-server") {
        ws?.close();
    } else if (event.data.source === "penpot") {
        document.body.dataset.theme = event.data.theme;
    } else if (event.data.type === "task-response") {
        // Forward task response back to MCP server
        sendTaskResponse(event.data.response);
    }
});

parent.postMessage({ type: "ui-initialized" }, "*");
