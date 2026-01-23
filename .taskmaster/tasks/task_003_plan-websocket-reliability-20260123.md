# Task ID: 3

**Title:** Implement automatic reconnection with exponential backoff

**Status:** pending

**Dependencies:** 1, 2

**Priority:** high

**Description:** Add scheduleReconnect() function and modify ws.onclose handler to trigger automatic reconnection attempts using exponential backoff.

**Details:**

Implement reconnection logic in main.ts:

```typescript
function scheduleReconnect(): void {
    // Don't schedule if already at max attempts
    if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
        connectionState = 'disconnected';
        updateConnectionStatus('disconnected', 'Max reconnection attempts reached. Click to reconnect.');
        return;
    }
    
    // Clear any existing reconnect timeout
    if (reconnectTimeoutId !== null) {
        clearTimeout(reconnectTimeoutId);
    }
    
    const delay = calculateReconnectDelay(reconnectAttempt);
    console.log(`Scheduling reconnect attempt ${reconnectAttempt + 1} in ${delay}ms`);
    
    reconnectTimeoutId = window.setTimeout(() => {
        reconnectAttempt++;
        connectionState = 'reconnecting';
        updateConnectionStatus('reconnecting');
        connectToMcpServer();
    }, delay);
}
```

Modify ws.onclose handler:
```typescript
ws.onclose = (event: CloseEvent) => {
    console.log('Disconnected from MCP server', event.code, event.reason);
    stopPingInterval();
    ws = null;
    
    // Only auto-reconnect on unexpected closures (code !== 1000)
    if (event.code !== 1000 && connectionState !== 'disconnected') {
        scheduleReconnect();
    } else {
        connectionState = 'disconnected';
        const message = event.reason || undefined;
        updateConnectionStatus('disconnected', message);
    }
};
```

**Test Strategy:**

1. Disconnect WebSocket server and verify plugin attempts reconnection
2. Verify exponential backoff timing: 1s, 2s, 4s, 8s, 16s delays
3. Verify reconnection stops after 10 attempts with appropriate error message
4. Test that clean disconnect (code 1000) does NOT trigger reconnection

## Subtasks

### 3.1. Implement scheduleReconnect() function with timer management

**Status:** pending  
**Dependencies:** None  

Create the scheduleReconnect() function that manages reconnection scheduling with exponential backoff timing, including proper timeout handling and state management.

**Details:**

Implement scheduleReconnect() in main.ts that: 1) Checks if reconnectAttempt >= MAX_RECONNECT_ATTEMPTS and updates state to 'disconnected' with appropriate message if so, 2) Clears any existing reconnectTimeoutId using clearTimeout before scheduling new one, 3) Calculates delay using calculateReconnectDelay(reconnectAttempt) from Task 1, 4) Uses window.setTimeout to schedule reconnection, incrementing reconnectAttempt, setting connectionState to 'reconnecting', calling updateConnectionStatus(), and invoking connectToMcpServer(). Store the timeout ID in reconnectTimeoutId for later cleanup.

### 3.2. Modify ws.onclose handler to trigger reconnection on unexpected closures

**Status:** pending  
**Dependencies:** 3.1  

Update the WebSocket onclose event handler to differentiate between clean disconnects (code 1000) and unexpected closures, triggering automatic reconnection only for unexpected ones.

**Details:**

Modify ws.onclose in connectToMcpServer() to: 1) Log disconnect with event.code and event.reason, 2) Call stopPingInterval() if implemented (add stub if not), 3) Set ws = null, 4) Check if event.code !== 1000 AND connectionState !== 'disconnected' to determine if reconnection should occur, 5) If conditions met, call scheduleReconnect(), 6) Otherwise set connectionState to 'disconnected' and call updateConnectionStatus('disconnected', event.reason || undefined). Code 1000 indicates normal closure initiated by client or clean server shutdown.

### 3.3. Add proper cleanup of reconnection timers on connection success or manual disconnect

**Status:** pending  
**Dependencies:** 3.1, 3.2  

Implement timer cleanup logic to clear pending reconnection timeouts when a connection succeeds or when the user manually disconnects.

**Details:**

Add cleanup in two locations: 1) In ws.onopen handler (preparing for Task 4): clear reconnectTimeoutId if not null using clearTimeout and set to null, 2) Create or update a disconnect/cleanup function that clears reconnectTimeoutId when user manually disconnects (sets connectionState to 'disconnected' before close), 3) Ensure no race conditions by checking reconnectTimeoutId !== null before clearing, 4) Add cleanup in window unload/beforeunload event if applicable to prevent orphaned timers. This ensures manual Connect button and successful reconnection both prevent duplicate connection attempts.
