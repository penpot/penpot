# Task ID: 7

**Title:** Implement server-side ping/pong handler

**Status:** pending

**Dependencies:** 6

**Priority:** medium

**Description:** Add ping message handling in PluginBridge.ts to respond with pong messages and clean up pending tasks on client disconnect.

**Details:**

Update PluginBridge.ts message handler to process ping messages:

```typescript
ws.on("message", (data: Buffer) => {
    this.logger.debug("Received WebSocket message: %s", data.toString());
    try {
        const message = JSON.parse(data.toString());
        
        // Handle ping messages
        if (message.type === 'ping') {
            const pongMessage = { type: 'pong', timestamp: message.timestamp };
            ws.send(JSON.stringify(pongMessage));
            this.logger.debug('Sent pong response');
            return;
        }
        
        // Handle task responses
        const response: PluginTaskResponse<any> = message;
        this.handlePluginTaskResponse(response);
    } catch (error) {
        this.logger.error(error, "Failure while processing WebSocket message");
    }
});
```

Add pending task cleanup on disconnect:
```typescript
ws.on("close", () => {
    this.logger.info("WebSocket connection closed");
    const connection = this.connectedClients.get(ws);
    this.connectedClients.delete(ws);
    if (connection?.userToken) {
        this.clientsByToken.delete(connection.userToken);
    }
    
    // Reject pending tasks for this connection with connection error
    this.rejectPendingTasksForConnection(ws);
});

private rejectPendingTasksForConnection(ws: WebSocket): void {
    for (const [taskId, task] of this.pendingTasks.entries()) {
        // Note: In single-user mode, reject all pending tasks
        // In multi-user mode, would need to track which tasks belong to which connection
        const timeoutHandle = this.taskTimeouts.get(taskId);
        if (timeoutHandle) {
            clearTimeout(timeoutHandle);
            this.taskTimeouts.delete(taskId);
        }
        this.pendingTasks.delete(taskId);
        task.rejectWithError(new Error('Connection lost - plugin disconnected'));
    }
}
```

**Test Strategy:**

1. Send ping from client, verify server responds with pong
2. Disconnect client while task is pending, verify task is rejected with 'Connection lost' error
3. Verify proper cleanup of task timeouts on disconnect
