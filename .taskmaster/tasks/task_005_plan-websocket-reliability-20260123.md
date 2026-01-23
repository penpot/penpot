# Task ID: 5

**Title:** Implement manual reconnect override

**Status:** pending

**Dependencies:** 3, 4

**Priority:** medium

**Description:** Update Connect button click handler to reset backoff timer and allow manual reconnection during any connection state.

**Details:**

Update the connect button click handler:

```typescript
document.querySelector("[data-handler='connect-mcp']")?.addEventListener("click", () => {
    // Clear any scheduled reconnection
    if (reconnectTimeoutId !== null) {
        clearTimeout(reconnectTimeoutId);
        reconnectTimeoutId = null;
    }
    
    // Reset attempt counter when user manually initiates connection
    reconnectAttempt = 0;
    
    // If already connected, disconnect first
    if (ws?.readyState === WebSocket.OPEN) {
        ws.close(1000, 'User initiated reconnection');
    }
    
    connectToMcpServer();
});
```

Optionally update index.html to dynamically show button text:
```html
<button type="button" data-appearance="secondary" data-handler="connect-mcp" id="connect-button">
    Connect to MCP server
</button>
```

And add logic to update button text based on state:
```typescript
function updateButtonText(): void {
    const button = document.getElementById('connect-button');
    if (!button) return;
    
    if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS && connectionState === 'disconnected') {
        button.textContent = 'Reconnect';
    } else {
        button.textContent = 'Connect to MCP server';
    }
}
```

**Test Strategy:**

1. Click Connect during active reconnection - verify it resets timer and reconnects immediately
2. After max retries exhausted, verify button changes to 'Reconnect'
3. Verify clicking Reconnect resets attempt counter and starts fresh
