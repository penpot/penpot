# Task ID: 1

**Title:** Add connection state management and constants

**Status:** pending

**Dependencies:** None

**Priority:** high

**Description:** Create connection state enum and constants for reconnection logic in main.ts, including max attempts, backoff delays, and ping intervals.

**Details:**

Add the following constants and state management to main.ts:

```typescript
// Connection state enum
type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'reconnecting';

// Constants
const MAX_RECONNECT_ATTEMPTS = 10;
const PING_INTERVAL_MS = 20000; // 20 seconds
const PONG_TIMEOUT_MS = 5000; // 5 seconds
const BASE_RECONNECT_DELAY_MS = 1000; // 1 second
const MAX_RECONNECT_DELAY_MS = 16000; // 16 seconds

// State variables
let connectionState: ConnectionState = 'disconnected';
let reconnectAttempt = 0;
let reconnectTimeoutId: number | null = null;
let pingIntervalId: number | null = null;
let pongTimeoutId: number | null = null;
```

Add helper function for exponential backoff calculation:
```typescript
function calculateReconnectDelay(attempt: number): number {
    const delay = BASE_RECONNECT_DELAY_MS * Math.pow(2, attempt);
    return Math.min(delay, MAX_RECONNECT_DELAY_MS);
}
```

**Test Strategy:**

1. Verify constants are exported/accessible
2. Unit test calculateReconnectDelay() returns correct values: attempt 0 = 1000ms, attempt 1 = 2000ms, attempt 2 = 4000ms, attempt 3 = 8000ms, attempt 4+ = 16000ms (capped)
