# Task ID: 8

**Title:** Add integration tests and cross-browser validation

**Status:** pending

**Dependencies:** 3, 5, 6, 7

**Priority:** low

**Description:** Create integration tests for the reconnection logic and validate behavior across Chrome, Firefox, and Safari browsers.

**Details:**

Create a test file or manual test plan covering:

1. **Reconnection scenarios**:
   - Server restart: Start plugin connected, restart server, verify auto-reconnect
   - Network blip: Simulate network interruption, verify recovery within 30s
   - Server unavailable: Start plugin with server down, verify retry behavior

2. **Browser-specific tests**:
   - Chrome: Test iframe WebSocket throttling behavior with background tab
   - Firefox: Verify reconnection in iframe context
   - Safari: Test WebSocket restrictions in iframes

3. **Edge cases**:
   - Rapid connect/disconnect cycles
   - Multiple manual reconnect clicks during reconnection
   - Connection during active keepalive ping

4. **Logging verification**:
   - Add console.log statements for all state transitions
   - Verify logs show: connection state changes, reconnect attempts, ping/pong exchanges

Consider adding E2E test using existing plugins/apps/e2e framework:
```typescript
describe('WebSocket Reconnection', () => {
    it('reconnects automatically after server disconnect', async () => {
        // Connect plugin
        // Stop server
        // Verify reconnection attempts
        // Start server
        // Verify successful reconnection
    });
});
```

**Test Strategy:**

1. Run manual test plan across Chrome, Firefox, Safari
2. Document any browser-specific issues or limitations
3. Verify success metrics: >99% uptime, <30s recovery, zero manual reconnection for idle timeouts
4. If E2E tests added, integrate into CI workflow

## Subtasks

### 8.1. Create test infrastructure for WebSocket reconnection scenarios

**Status:** pending  
**Dependencies:** None  

Set up the test harness and utilities needed to simulate WebSocket connection states, server restarts, and network interruptions for testing reconnection logic.

**Details:**

Create test infrastructure in plugins/apps/e2e or a new test directory:

1. **Mock WebSocket server setup**:
   - Create a controllable test server that can be started/stopped programmatically
   - Add methods: start(), stop(), simulateDisconnect(), getConnectionCount()

2. **Test utilities**:
   - Helper to wait for connection state changes
   - Helper to capture and verify console.log output for state transitions
   - Timing utilities for verifying backoff delays

3. **Test fixture setup**:
   ```typescript
   // test-utils/websocket-test-server.ts
   export class TestWebSocketServer {
     private server: WebSocketServer | null = null;
     
     async start(port: number): Promise<void>
     async stop(): Promise<void>
     async restart(): Promise<void>
     simulateLatency(ms: number): void
   }
   ```

4. **Integration with existing e2e Agent pattern** if applicable

### 8.2. Write integration tests for server restart and network blip scenarios

**Status:** pending  
**Dependencies:** 8.1  

Implement integration tests covering automatic reconnection after server restart, network interruption recovery within 30 seconds, and retry behavior when server is unavailable.

**Details:**

Create integration tests using the test infrastructure:

1. **Server restart test**:
   ```typescript
   describe('WebSocket Reconnection', () => {
     it('reconnects automatically after server disconnect', async () => {
       // Connect plugin
       await connectPlugin();
       expect(connectionState).toBe('connected');
       
       // Stop server
       await testServer.stop();
       await waitForState('reconnecting');
       
       // Verify reconnection attempts
       expect(reconnectAttempt).toBeGreaterThan(0);
       
       // Start server
       await testServer.start();
       
       // Verify successful reconnection within 30s
       await waitForState('connected', { timeout: 30000 });
     });
   });
   ```

2. **Network blip test**: Simulate brief disconnection (<5s), verify recovery

3. **Server unavailable test**: Start plugin with server down, verify retry with exponential backoff (1s, 2s, 4s, 8s, 16s cap)

4. **Verify logging**: Assert console output shows state transitions and attempt counts

### 8.3. Write edge case tests for rapid connect/disconnect and concurrent operations

**Status:** pending  
**Dependencies:** 8.1  

Create tests for edge cases including rapid connect/disconnect cycles, multiple manual reconnect clicks during reconnection, and connection attempts during active keepalive pings.

**Details:**

Implement edge case tests:

1. **Rapid connect/disconnect cycles**:
   ```typescript
   it('handles rapid connect/disconnect cycles without state corruption', async () => {
     for (let i = 0; i < 10; i++) {
       await clickConnect();
       await waitForState('connecting');
       await testServer.simulateDisconnect();
     }
     // Verify final state is consistent
     expect(['disconnected', 'reconnecting']).toContain(connectionState);
     expect(reconnectAttempt).toBeLessThanOrEqual(MAX_RECONNECT_ATTEMPTS);
   });
   ```

2. **Multiple manual reconnect clicks**:
   - Click Connect multiple times while reconnecting
   - Verify no duplicate connections created
   - Verify reconnect timer is properly cleared/reset

3. **Connection during keepalive ping**:
   - Trigger disconnect exactly during ping/pong exchange
   - Verify ping interval is cleared and reconnection starts cleanly

4. **Max retries exhaustion**:
   - Verify behavior after 10 failed attempts
   - Verify manual reconnect resets counter

### 8.4. Create cross-browser test plan document and manual testing checklist

**Status:** pending  
**Dependencies:** None  

Document a comprehensive manual testing checklist for validating WebSocket reconnection behavior across Chrome, Firefox, and Safari browsers.

**Details:**

Create test plan document at `.claude/docs/websocket-cross-browser-test-plan.md`:

1. **Test environment setup**:
   - Browser versions to test (latest stable of each)
   - Penpot plugin installation steps
   - How to access browser dev console for logging

2. **Manual test checklist** (for each browser):
   - [ ] Basic connection: Plugin connects on load
   - [ ] Server restart: Stop MCP server, verify reconnection UI shows attempts, restart server, verify auto-reconnect
   - [ ] Network blip: Disconnect network briefly, verify recovery
   - [ ] Max retries: Keep server down, verify stops after 10 attempts
   - [ ] Manual override: Click Connect during reconnection, verify immediate retry

3. **Success criteria**:
   - >99% uptime measurement methodology
   - <30s recovery time verification
   - Zero manual reconnection needed for idle timeouts

4. **Issue reporting template**: Browser, version, steps, expected vs actual, console logs

### 8.5. Implement and document browser-specific WebSocket behaviors

**Status:** pending  
**Dependencies:** 8.4  

Test and document browser-specific WebSocket behaviors including Chrome iframe throttling with background tabs, Firefox iframe reconnection, and Safari WebSocket restrictions.

**Details:**

Create documentation and any necessary workarounds:

1. **Chrome background tab throttling**:
   - Test: Open plugin in iframe, switch to another tab, observe WebSocket behavior
   - Document: Timer throttling to 1/second in background tabs
   - Verify: Keepalive ping (20s interval) works despite throttling
   - Add to test plan: Specific steps to reproduce and verify

2. **Firefox iframe context**:
   - Test: WebSocket reconnection within Penpot plugin iframe
   - Document any permission or security considerations
   - Verify: Same reconnection behavior as top-level context

3. **Safari restrictions**:
   - Test: WebSocket behavior in Safari iframes
   - Document: Any ITP (Intelligent Tracking Prevention) impacts
   - Document: Third-party iframe WebSocket limitations
   - Add workarounds if needed (e.g., first-party subdomains)

4. **Update CLAUDE.md or README**: Add browser compatibility notes section
