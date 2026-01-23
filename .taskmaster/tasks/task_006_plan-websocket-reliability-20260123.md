# Task ID: 6

**Title:** Implement keepalive ping mechanism (client-side)

**Status:** pending

**Dependencies:** 4

**Priority:** high

**Description:** Add ping interval that sends periodic ping messages and monitors for pong responses to detect stale connections.

**Details:**

Add ping/pong handling in main.ts:

```typescript
function startPingInterval(): void {
    stopPingInterval(); // Clear any existing interval
    
    pingIntervalId = window.setInterval(() => {
        if (ws?.readyState === WebSocket.OPEN) {
            const pingMessage = { type: 'ping', timestamp: Date.now() };
            ws.send(JSON.stringify(pingMessage));
            console.log('Sent ping');
            
            // Set timeout for pong response
            pongTimeoutId = window.setTimeout(() => {
                console.warn('Pong timeout - connection may be dead');
                // Force close and trigger reconnection
                if (ws) {
                    ws.close(4000, 'Pong timeout');
                }
            }, PONG_TIMEOUT_MS);
        }
    }, PING_INTERVAL_MS);
}

function stopPingInterval(): void {
    if (pingIntervalId !== null) {
        clearInterval(pingIntervalId);
        pingIntervalId = null;
    }
    if (pongTimeoutId !== null) {
        clearTimeout(pongTimeoutId);
        pongTimeoutId = null;
    }
}
```

Update ws.onmessage to handle pong responses:
```typescript
ws.onmessage = (event) => {
    console.log('Received from MCP server:', event.data);
    try {
        const message = JSON.parse(event.data);
        
        // Handle pong response
        if (message.type === 'pong') {
            console.log('Received pong');
            if (pongTimeoutId !== null) {
                clearTimeout(pongTimeoutId);
                pongTimeoutId = null;
            }
            return;
        }
        
        // Forward task requests to plugin
        parent.postMessage(message, '*');
    } catch (error) {
        console.error('Failed to parse WebSocket message:', error);
    }
};
```

**Test Strategy:**

1. Verify ping messages sent every 20 seconds when connected
2. Verify pong timeout (5s) triggers connection close if no response
3. Verify pong response clears the timeout timer
4. Test that ping interval stops on disconnect

## Subtasks

### 6.1. Implement startPingInterval() and stopPingInterval() timer management functions

**Status:** pending  
**Dependencies:** None  

Create the core ping/pong timer functions that manage the ping interval and pong timeout timers with proper cleanup to prevent memory leaks.

**Details:**

Add module-level timer ID variables (pingIntervalId, pongTimeoutId) at the top of main.ts near the existing ws variable. Implement startPingInterval() that: 1) Calls stopPingInterval() first to clear any existing timers, 2) Uses window.setInterval() with PING_INTERVAL_MS to periodically send JSON ping messages {type: 'ping', timestamp: Date.now()}, 3) Only sends if ws?.readyState === WebSocket.OPEN, 4) After sending each ping, sets a pong timeout using window.setTimeout() with PONG_TIMEOUT_MS that will call ws.close(4000, 'Pong timeout') if no pong received. Implement stopPingInterval() that clears both timers using clearInterval/clearTimeout and sets IDs back to null. Both functions must handle null timer IDs gracefully.

### 6.2. Add pong message handling in ws.onmessage to clear timeout and acknowledge keepalive

**Status:** pending  
**Dependencies:** 6.1  

Modify the existing ws.onmessage handler to detect and handle pong response messages, clearing the pong timeout to prevent false connection-dead detection.

**Details:**

Update the ws.onmessage handler (currently at line 68-77) to: 1) Parse the incoming JSON message, 2) Check if message.type === 'pong' before forwarding to parent, 3) If pong received: log acknowledgment, clear the pongTimeoutId using clearTimeout(), set pongTimeoutId to null, and return early without forwarding to plugin, 4) For all other messages, continue with existing behavior (forward task requests to parent via postMessage). The pong handler must be checked BEFORE the parent.postMessage() call to avoid forwarding keepalive messages as task requests. Consider logging the round-trip time using message.timestamp if desired for debugging.

### 6.3. Integrate ping/pong lifecycle with WebSocket connection open/close events

**Status:** pending  
**Dependencies:** 6.1, 6.2  

Wire up the ping interval to start when connection opens and stop when connection closes, ensuring proper cleanup and no orphaned timers.

**Details:**

Modify ws.onopen handler (line 63-66) to call startPingInterval() after successful connection, ensuring keepalive begins immediately when connected. Modify ws.onclose handler (line 79-84) to call stopPingInterval() before setting ws = null, ensuring timers are cleaned up on any disconnection. Also call stopPingInterval() in ws.onerror (line 86-90) as a safety measure. Add stopPingInterval() call at the start of connectToMcpServer() to handle edge cases where previous connection's timers weren't cleaned up. This ensures the ping/pong lifecycle is fully synchronized with the WebSocket connection state.
