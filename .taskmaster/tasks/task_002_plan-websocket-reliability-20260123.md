# Task ID: 2

**Title:** Update connection status UI with reconnection states

**Status:** pending

**Dependencies:** 1

**Priority:** high

**Description:** Enhance updateConnectionStatus() function to display reconnection attempt count and color-code connection states (green/yellow/red).

**Details:**

Modify the updateConnectionStatus function to handle all connection states:

```typescript
function updateConnectionStatus(state: ConnectionState, message?: string): void {
    if (!statusElement) return;
    
    let displayText: string;
    let color: string;
    
    switch (state) {
        case 'connected':
            displayText = 'Connected to MCP server';
            color = 'var(--accent-primary)'; // green
            break;
        case 'connecting':
            displayText = 'Connecting...';
            color = 'var(--warning-500, #f59e0b)'; // yellow
            break;
        case 'reconnecting':
            displayText = `Reconnecting (attempt ${reconnectAttempt}/${MAX_RECONNECT_ATTEMPTS})...`;
            color = 'var(--warning-500, #f59e0b)'; // yellow
            break;
        case 'disconnected':
            displayText = message || 'Disconnected';
            color = 'var(--error-700)'; // red
            break;
    }
    
    if (message && state !== 'disconnected') {
        displayText += `: ${message}`;
    }
    
    statusElement.textContent = displayText;
    statusElement.style.color = color;
}
```

Update index.html button to show appropriate text based on state (Connect vs Reconnect).

**Test Strategy:**

1. Visual inspection: verify green color when connected, yellow when connecting/reconnecting, red when disconnected
2. Verify reconnection attempt counter is displayed correctly as 'Reconnecting (attempt X/10)...'
3. Test that status text updates in real-time during reconnection sequence
